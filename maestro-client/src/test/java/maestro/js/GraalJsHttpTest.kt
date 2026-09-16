package maestro.js

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.TimeUnit

class GraalJsHttpTest {

    private val parent = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()

    private val http = GraalJsHttp(parent)

    @Test
    fun `no timeout reuses the shared client`() {
        assertThat(http.clientFor(null)).isSameInstanceAs(parent)
    }

    @Test
    fun `a timeout applies to every phase of the call`() {
        val client = http.clientFor(900_000)

        assertThat(client.callTimeoutMillis).isEqualTo(900_000)
        assertThat(client.connectTimeoutMillis).isEqualTo(900_000)
        assertThat(client.readTimeoutMillis).isEqualTo(900_000)
        assertThat(client.writeTimeoutMillis).isEqualTo(900_000)
    }

    @Test
    fun `a timeout shorter than the default connect timeout still shortens connect`() {
        // Guards the case a timeout is meant to make a call fail faster, not only wait longer.
        val client = http.clientFor(250)

        assertThat(client.connectTimeoutMillis).isEqualTo(250)
    }

    @Test
    fun `derived clients share the parent's connection pool and dispatcher`() {
        val client = http.clientFor(1_000)

        assertThat(client.connectionPool).isSameInstanceAs(parent.connectionPool)
        assertThat(client.dispatcher).isSameInstanceAs(parent.dispatcher)
        assertThat(client.eventListenerFactory).isSameInstanceAs(parent.eventListenerFactory)
        assertThat(client.interceptors).isEqualTo(parent.interceptors)
        assertThat(client.networkInterceptors).isEqualTo(parent.networkInterceptors)
    }

    @Test
    fun `equal timeouts are derived once`() {
        // Guards flows that make the same call in a long `repeat` loop.
        val first = http.clientFor(1_000)
        val second = http.clientFor(1_000)

        assertThat(second).isSameInstanceAs(first)
    }

    @Test
    fun `different timeouts get different clients`() {
        val first = http.clientFor(1_000)
        val second = http.clientFor(2_000)

        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second.callTimeoutMillis).isEqualTo(2_000)
    }

    @Test
    fun `the default timeout applies when the request sets none`() {
        val withDefault = GraalJsHttp(parent, defaultTimeoutMs = 45_000)

        val client = withDefault.clientFor(withDefault.timeoutMsOf(null) ?: 45_000)

        assertThat(client.callTimeoutMillis).isEqualTo(45_000)
    }

    @Test
    fun `the request param wins over the default`() {
        val withDefault = GraalJsHttp(parent, defaultTimeoutMs = 45_000)

        assertThat(withDefault.timeoutMsOf(mapOf("timeout" to 1_000))).isEqualTo(1_000L)
    }

    @Test
    fun `no timeout param resolves to null`() {
        assertThat(http.timeoutMsOf(null)).isNull()
        assertThat(http.timeoutMsOf(mapOf("body" to "{}"))).isNull()
    }

    @Test
    fun `reads the timeout param as a number`() {
        assertThat(http.timeoutMsOf(mapOf("timeout" to 900_000))).isEqualTo(900_000L)
    }

    @Test
    fun `reads a JS number that arrives as a double`() {
        // GraalJS hands numeric literals to host code as Integer or Double depending on the value.
        assertThat(http.timeoutMsOf(mapOf("timeout" to 1_500.0))).isEqualTo(1_500L)
    }

    @Test
    fun `reads a numeric string, which is what a loosely typed caller may pass`() {
        assertThat(http.timeoutMsOf(mapOf("timeout" to " 45000 "))).isEqualTo(45_000L)
    }

    @Test
    fun `rejects a non-numeric timeout instead of silently ignoring it`() {
        val error = assertThrows<IllegalArgumentException> {
            http.timeoutMsOf(mapOf("timeout" to "soon"))
        }

        assertThat(error).hasMessageThat().contains("`timeout`")
        assertThat(error).hasMessageThat().contains("soon")
    }

    @Test
    fun `rejects a timeout of the wrong type`() {
        val error = assertThrows<IllegalArgumentException> {
            http.timeoutMsOf(mapOf("timeout" to true))
        }

        assertThat(error).hasMessageThat().contains("`timeout`")
    }

    @Test
    fun `rejects a non-positive timeout`() {
        assertThrows<IllegalArgumentException> { http.timeoutMsOf(mapOf("timeout" to 0)) }
        assertThrows<IllegalArgumentException> { http.timeoutMsOf(mapOf("timeout" to -1)) }
    }
}
