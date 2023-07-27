package maestro.test

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo
import com.google.common.net.HttpHeaders
import com.google.common.truth.Truth.assertThat
import maestro.js.RhinoJsEngine
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mozilla.javascript.RhinoException

class RhinoJsEngineTest : JsEngineTest() {

    @BeforeEach
    fun setUp() {
        engine = RhinoJsEngine()
    }

    @Test
    fun `Redefinitions of variables are not allowed`() {
        engine.evaluateScript("const foo = null")

        assertThrows<RhinoException> {
            engine.evaluateScript("const foo = null")
        }
    }

    @Test
    fun `You can access variables across scopes`() {
        engine.evaluateScript("const foo = 'foo'")
        assertThat(engine.evaluateScript("foo")).isEqualTo("foo")

        engine.enterScope()
        assertThat(engine.evaluateScript("foo")).isEqualTo("foo")
    }

    @Test
    fun `Backslash and newline are not supported`() {
        assertThrows<RhinoException> {
            engine.setCopiedText("\\")
        }

        assertThrows<RhinoException> {
            engine.putEnv("FOO", "\\")
        }

        engine.setCopiedText("\n")
        engine.putEnv("FOO", "\n")

        val result = engine.evaluateScript("maestro.copiedText + FOO").toString()

        assertThat(result).isEqualTo("")
    }

    @Test
    fun `parseInt returns a double representation`() {
        val result = engine.evaluateScript("parseInt('1')").toString()
        assertThat(result).isEqualTo("1.0")
    }
}