package maestro.js

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

class GraalJsHttpTest {

    private val parent = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    private val http = GraalJsHttp(parent)

    @Test
    fun `default options reuse the shared client`() {
        assertThat(http.clientFor(JsHttpOptions.DEFAULT)).isSameInstanceAs(parent)
    }

    @Test
    fun `a timeout override applies to the call, read and write timeouts`() {
        val client = http.clientFor(JsHttpOptions(timeoutMs = 900_000))

        assertThat(client.callTimeoutMillis).isEqualTo(900_000)
        assertThat(client.readTimeoutMillis).isEqualTo(900_000)
        assertThat(client.writeTimeoutMillis).isEqualTo(900_000)
    }

    @Test
    fun `a timeout override leaves the connect timeout alone`() {
        val client = http.clientFor(JsHttpOptions(timeoutMs = 900_000))

        assertThat(client.connectTimeoutMillis).isEqualTo(parent.connectTimeoutMillis)
    }

    @Test
    fun `derived clients share the parent's connection pool and dispatcher`() {
        val client = http.clientFor(JsHttpOptions(timeoutMs = 1_000))

        assertThat(client.connectionPool).isSameInstanceAs(parent.connectionPool)
        assertThat(client.dispatcher).isSameInstanceAs(parent.dispatcher)
        assertThat(client.eventListenerFactory).isSameInstanceAs(parent.eventListenerFactory)
        assertThat(client.interceptors).isEqualTo(parent.interceptors)
        assertThat(client.networkInterceptors).isEqualTo(parent.networkInterceptors)
    }

    @Test
    fun `equal options are derived once`() {
        // Guards flows that make the same call in a long `repeat` loop.
        val first = http.clientFor(JsHttpOptions(timeoutMs = 1_000))
        val second = http.clientFor(JsHttpOptions(timeoutMs = 1_000))

        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun `different options get different clients`() {
        val first = http.clientFor(JsHttpOptions(timeoutMs = 1_000))
        val second = http.clientFor(JsHttpOptions(timeoutMs = 2_000))

        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second.callTimeoutMillis).isEqualTo(2_000)
    }
}
