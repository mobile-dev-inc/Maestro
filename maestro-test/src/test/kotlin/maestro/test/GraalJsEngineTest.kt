package maestro.test

import com.google.common.truth.Truth.assertThat
import maestro.js.GraalJsEngine
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GraalJsEngineTest : JsEngineTest() {

    @BeforeEach
    fun setUp() {
        engine = GraalJsEngine()
    }

    @Test
    fun `Allow redefinitions of variables`() {
        engine.evaluateScript("const foo = null")
        engine.evaluateScript("const foo = null")
    }

    @Test
    fun `You can't share variables between scopes`() {
        engine.evaluateScript("const foo = 'foo'")
        val result = engine.evaluateScript("foo").toString()
        assertThat(result).contains("undefined")
    }

    @Test
    fun `Backslash and newline are supported`() {
        engine.setCopiedText("\\\n")
        engine.putEnv("FOO", "\\\n")

        val result = engine.evaluateScript("maestro.copiedText + FOO").toString()

        assertThat(result).isEqualTo("\\\n\\\n")
    }

    @Test
    fun `parseInt returns an int representation`() {
        val result = engine.evaluateScript("parseInt('1')").toString()
        assertThat(result).isEqualTo("1")
    }

    @Test
    fun `sandboxing works`() {
        try {
            engine.evaluateScript("require('fs')")
            assert(false)
        } catch (e: PolyglotException) {
            assertThat(e.message).contains("undefined is not a function")
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
        assertThat(engine.evaluateScript("ROOT_VAR").toString()).isEqualTo("root_value")
        assertThat(engine.evaluateScript("SCOPED_VAR").toString()).isEqualTo("scoped_value")
        
        // Leave the env scope
        engine.leaveEnvScope()
        
        // Root variable should still be accessible
        assertThat(engine.evaluateScript("ROOT_VAR").toString()).isEqualTo("root_value")
        
        // Scoped variable should no longer be accessible (undefined)
        assertThat(engine.evaluateScript("SCOPED_VAR").toString()).contains("undefined")
    }

    @Test
    fun `Can user faker providers`() {
        val result = engine.evaluateScript("faker.name().firstName()").toString()
        assertThat(result).matches("^[A-Za-z]+$")
    }

    @Test
    fun `Can evaluate faker expressions`() {
        val result = engine.evaluateScript("faker.expression('#{name.firstName} #{name.lastName}')").toString()
        assertThat(result).matches("^[A-Za-z]+ [A-Za-z']+$")
    }

    @Test
    fun `runInSubScope should isolate environment variables`() {
        // Set a base environment variable
        engine.putEnv("MY_VAR", "original")
        
        // Verify original value is accessible
        assertThat(engine.evaluateScript("MY_VAR").toString()).isEqualTo("original")
        
        // Execute script with runInSubScope=true and different env var
        val envVars = mapOf("MY_VAR" to "scoped")
        engine.evaluateScript("console.log('Log from runScript')", envVars, "test.js", runInSubScope = true)
        
        // MY_VAR should still be original - the scoped value should not leak
        assertThat(engine.evaluateScript("MY_VAR").toString()).isEqualTo("original")
    }

}