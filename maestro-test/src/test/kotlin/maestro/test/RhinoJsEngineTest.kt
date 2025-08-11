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

    @Test
    fun `sandboxing works`() {
        try {
            engine.evaluateScript("require('fs')")
            assert(false)
        } catch (e: RhinoException) {
            assertThat(e.message).contains("TypeError: require is not a function, it is object. (inline-script#1)")
        }
    }

    @Test
    fun `Environment variables are isolated between env scopes`() {
        // Set a variable in the root scope
        engine.putEnv("ROOT_VAR", "root_value")
        
        // Enter new env scope and set a variable
        engine.enterEnvScope()
        engine.putEnv("SCOPED_VAR", "scoped_value")
        
        // Both variables should be accessible in the child scope
        assertThat(engine.evaluateScript("ROOT_VAR")).isEqualTo("root_value")
        assertThat(engine.evaluateScript("SCOPED_VAR")).isEqualTo("scoped_value")
        
        // Leave the env scope
        engine.leaveEnvScope()
        
        // Root variable should still be accessible
        assertThat(engine.evaluateScript("ROOT_VAR")).isEqualTo("root_value")
        
        // Scoped variable should no longer be accessible (null in RhinoJS due to scope mechanism)
        assertThat(engine.evaluateScript("SCOPED_VAR")).isNull()
    }
}