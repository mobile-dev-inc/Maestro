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
    fun `Can execute faker`() {
        val result = engine.evaluateScript("datafaker.generate('#{} potato')").toString()
        assertThat(result).isEqualTo(" potato")
    }

    @Test
    fun `Can evaluate faker expressions`() {
        val result = engine.evaluateScript("datafaker.generate('#{name.firstName}')").toString()
        assertThat(result).matches("^[A-Za-z]+$")
    }

    @Test
    fun `Can evaluate multiple faker expressions`() {
        val result = engine.evaluateScript("datafaker.generate('#{name.firstName} #{name.lastName}')").toString()
        assertThat(result).matches("^[A-Za-z]+ [A-Za-z']+$")
    }

    @Test
    fun `Can evaluate faker expressions with parameters`() {
        val result = engine.evaluateScript("datafaker.generate(\"#{internet.emailaddress 'potato'}\")").toString()
        assertThat(result).matches("^potato@[a-z]+\\.[a-z]+$")
    }

    @Test
    fun `Can evaluate faker expressions that return structured JSON`() {
        val result = engine.evaluateScript("datafaker.generate(\"#{json 'person','#{json ''first_name'',''#{Name.first_name}'',''last_name'',''#{Name.last_name}''}'}\")").toString()
        assertThat(result).matches("^\\{\"person\": \\{\"first_name\": \"[A-Za-z]+\", \"last_name\": \"[A-Za-z']+\"}}$")
    }

    @Test
    fun `Will automatically wrap strings without expressions as faker expressions`() {
        val result = engine.evaluateScript("datafaker.generate('name.firstName')").toString()
        assertThat(result).matches("^[A-Za-z]+$")
    }

    @Test
    fun `Will not wrap strings with expressions as faker expressions`() {
        val result = engine.evaluateScript("datafaker.generate('name.firstName #{name.lastName}')").toString()
        assertThat(result).matches("^name.firstName [A-Za-z']+$")
    }

    @Test
    fun `Has a convenience wrapper for numbers`() {
        val result = engine.evaluateScript("datafaker.number()").toString()
        assertThat(result).matches("^[0-9]{8}$")
    }

    @Test
    fun `Has a convenience wrapper for numbers that accepts a length argument`() {
        val result = engine.evaluateScript("datafaker.number(16)").toString()
        assertThat(result).matches("^[0-9]{16}$")
    }

    @Test
    fun `Has a convenience wrapper for text`() {
        val result = engine.evaluateScript("datafaker.text()").toString()
        assertThat(result).matches("^[a-z]{8}$")
    }

    @Test
    fun `Has a convenience wrapper for text that accepts a length argument`() {
        val result = engine.evaluateScript("datafaker.text(5)").toString()
        assertThat(result).matches("^[a-z]{5}$")
    }

    @Test
    fun `Has a convenience wrapper for email`() {
        val result = engine.evaluateScript("datafaker.email()").toString()
        assertThat(result).matches("^[a-z]+\\.[a-z]+@[a-z]+\\.[a-z]+$")
    }

    @Test
    fun `Has a convenience wrapper for names`() {
        val result = engine.evaluateScript("datafaker.personName()").toString()
        assertThat(result).matches("^([A-Za-z.]+ )?[A-Za-z]+ [A-Za-z']+( [A-Za-z.]+)?$") // Matches optional title, first name, and last name, and optional suffix
    }

    @Test
    fun `Has a convenience wrapper for cities`() {
        val result = engine.evaluateScript("datafaker.city()").toString()
        assertThat(result).matches("^[A-Z][a-z]+( [A-Z][a-z]+)*$") // Matches city names with optional multiple words
    }

    @Test
    fun `Has a convenience wrapper for countries`() {
        val result = engine.evaluateScript("datafaker.country()").toString()
        assertThat(result).matches("^[A-Z][a-z,]+( [A-Za-z,()]+)*$") // Matches country names with optional multiple words
    }

    @Test
    fun `Has a convenience wrapper for colours`() {
        val result = engine.evaluateScript("datafaker.color()").toString()
        assertThat(result).matches("^[a-z]+( [a-z]+)*$") // Matches single or multiple word color names
    }
}