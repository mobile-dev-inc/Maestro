import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.micrometer.core.instrument.Counter
import maestro.utils.HttpClient
import maestro.utils.Metrics
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import kotlin.time.Duration.Companion.milliseconds

class TransientFailureRetryInterceptorTest {

    private lateinit var server: MockWebServer
    private lateinit var metrics: Metrics
    private lateinit var counter: Counter

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        counter = mockk(relaxed = true)
        metrics = mockk {
            every { counter(any(), any()) } returns counter
            every { timer(any(), any()) } returns mockk(relaxed = true)
            every { withPrefix(any()) } returns this
            every { withTags(any()) } returns this
        }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun buildClient(retry: Boolean = true): OkHttpClient = HttpClient.build(
        name = "test",
        retryOnTransientFailure = retry,
        retryMaxAttempts = 3,
        retryInitialBackoff = 1.milliseconds,
        retryMaxBackoff = 1.milliseconds,
        metrics = metrics,
    )

    private fun call(client: OkHttpClient) = client.newCall(
        Request.Builder().url(server.url("/test")).build()
    ).execute()

    @Test
    fun `5xx on first attempt then 200 returns 200 in one logical call`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("server busy"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = call(buildClient())

        assertThat(response.code).isEqualTo(200)
        assertThat(response.body?.string()).isEqualTo("ok")
        assertThat(server.requestCount).isEqualTo(2)
        verify(exactly = 1) {
            metrics.counter(
                "http.client.retries",
                match { it["kind"] == "http_503" }
            )
        }
    }

    @Test
    fun `5xx for every attempt surfaces the last response after maxAttempts`() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503).setBody("still busy")) }

        val response = call(buildClient())

        assertThat(response.code).isEqualTo(503)
        assertThat(server.requestCount).isEqualTo(3)
        // 2 retries recorded (attempts 1 and 2). The 3rd attempt is the final one — no retry logged.
        verify(exactly = 2) {
            metrics.counter(
                "http.client.retries",
                match { it["kind"] == "http_503" }
            )
        }
    }

    @Test
    fun `4xx is not retried and returns immediately`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad request"))

        val response = call(buildClient())

        assertThat(response.code).isEqualTo(400)
        assertThat(server.requestCount).isEqualTo(1)
        verify(exactly = 0) { metrics.counter("http.client.retries", any()) }
    }

    @Test
    fun `IOException retries up to maxAttempts then surfaces`() {
        repeat(3) {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        }

        assertThrows<IOException> { call(buildClient()) }

        assertThat(server.requestCount).isEqualTo(3)
        verify(exactly = 2) { metrics.counter("http.client.retries", any()) }
    }

    @Test
    fun `IOException recovers after a retry if next attempt succeeds`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        server.enqueue(MockResponse().setResponseCode(200).setBody("recovered"))

        val response = call(buildClient())

        assertThat(response.code).isEqualTo(200)
        assertThat(response.body?.string()).isEqualTo("recovered")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test
    fun `retry disabled passes 5xx through unchanged`() {
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))

        val response = call(buildClient(retry = false))

        assertThat(response.code).isEqualTo(503)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `2xx on first attempt makes exactly one call`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val response = call(buildClient())

        assertThat(response.code).isEqualTo(200)
        assertThat(server.requestCount).isEqualTo(1)
        verify(exactly = 0) { metrics.counter("http.client.retries", any()) }
    }
}
