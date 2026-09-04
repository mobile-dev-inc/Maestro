package maestro.js

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class JsHttpOptionsTest {

    private val noEnv = emptyMap<String, String>()

    @Test
    fun `resolves to the default when nothing is set`() {
        assertThat(JsHttpOptions.resolve(null, noEnv)).isEqualTo(JsHttpOptions.DEFAULT)
        assertThat(JsHttpOptions.resolve(mapOf("body" to "{}"), noEnv)).isEqualTo(JsHttpOptions.DEFAULT)
    }

    @Test
    fun `reads the timeout param as a number`() {
        val options = JsHttpOptions.resolve(mapOf("timeout" to 900_000), noEnv)

        assertThat(options.timeoutMs).isEqualTo(900_000L)
    }

    @Test
    fun `reads a JS number that arrives as a double`() {
        // GraalJS hands numeric literals to host code as Integer or Double depending on the value.
        val options = JsHttpOptions.resolve(mapOf("timeout" to 1_500.0), noEnv)

        assertThat(options.timeoutMs).isEqualTo(1_500L)
    }

    @Test
    fun `reads the timeout from the flow env when no param is given`() {
        val options = JsHttpOptions.resolve(null, mapOf("MAESTRO_JS_HTTP_TIMEOUT" to "45000"))

        assertThat(options.timeoutMs).isEqualTo(45_000L)
    }

    @Test
    fun `the timeout param overrides the flow env`() {
        val options = JsHttpOptions.resolve(
            mapOf("timeout" to 1_000),
            mapOf("MAESTRO_JS_HTTP_TIMEOUT" to "45000"),
        )

        assertThat(options.timeoutMs).isEqualTo(1_000L)
    }

    @Test
    fun `tolerates surrounding whitespace in an env value`() {
        val options = JsHttpOptions.resolve(null, mapOf("MAESTRO_JS_HTTP_TIMEOUT" to " 45000 "))

        assertThat(options.timeoutMs).isEqualTo(45_000L)
    }

    @Test
    fun `treats a blank env value as unset`() {
        val options = JsHttpOptions.resolve(null, mapOf("MAESTRO_JS_HTTP_TIMEOUT" to "   "))

        assertThat(options).isEqualTo(JsHttpOptions.DEFAULT)
    }

    @Test
    fun `rejects a non-numeric timeout param instead of silently ignoring it`() {
        val error = assertThrows<IllegalArgumentException> {
            JsHttpOptions.resolve(mapOf("timeout" to "soon"), noEnv)
        }

        assertThat(error).hasMessageThat().contains("`timeout` param")
        assertThat(error).hasMessageThat().contains("soon")
    }

    @Test
    fun `rejects a non-numeric env value and names the variable`() {
        val error = assertThrows<IllegalArgumentException> {
            JsHttpOptions.resolve(null, mapOf("MAESTRO_JS_HTTP_TIMEOUT" to "5m"))
        }

        assertThat(error).hasMessageThat().contains("MAESTRO_JS_HTTP_TIMEOUT")
    }

    @Test
    fun `rejects a timeout param of the wrong type`() {
        val error = assertThrows<IllegalArgumentException> {
            JsHttpOptions.resolve(mapOf("timeout" to true), noEnv)
        }

        assertThat(error).hasMessageThat().contains("`timeout` param")
    }

    @Test
    fun `rejects a non-positive timeout`() {
        assertThrows<IllegalArgumentException> {
            JsHttpOptions.resolve(mapOf("timeout" to 0), noEnv)
        }
        assertThrows<IllegalArgumentException> {
            JsHttpOptions.resolve(null, mapOf("MAESTRO_JS_HTTP_TIMEOUT" to "-1"))
        }
    }
}
