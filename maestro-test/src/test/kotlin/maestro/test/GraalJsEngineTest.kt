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

    @Test
    fun `contexts are automatically closed when shared bindings are not modified`() {
        // This test simulates a repeat loop with a condition like:
        //   - repeat:
        //       while:
        //         notVisible: '${MARKET}'
        //       commands:
        //         - swipe: ...
        //
        // The ${MARKET} expression is evaluated on every iteration.
        //
        // BEFORE FIX: Each evaluateScript() created a new GraalJS context that was
        // never released until close() was called. This caused:
        //   - 100 evaluations = 100 open contexts
        //   - ~68 MB memory growth for just 100 simple evaluations (~0.68 MB each)
        //   - OOM errors for flows with 1000+ iterations
        //
        // AFTER FIX: Contexts are automatically closed after evaluation when shared
        // bindings (output, maestro) are not modified, keeping memory bounded.

        val graalEngine = engine as GraalJsEngine
        graalEngine.putEnv("MARKET", "some_value")

        // Force GC and measure baseline memory
        System.gc()
        Thread.sleep(100)
        val runtime = Runtime.getRuntime()
        val baselineMemory = runtime.totalMemory() - runtime.freeMemory()

        val iterations = 100
        repeat(iterations) {
            graalEngine.evaluateScript("MARKET")
        }

        // Measure memory after evaluations
        System.gc()
        Thread.sleep(100)
        val finalMemory = runtime.totalMemory() - runtime.freeMemory()
        val memoryGrowthMB = (finalMemory - baselineMemory) / (1024.0 * 1024.0)

        val contextCount = graalEngine.openContextCount()

        // Log the results for visibility
        println(
            "After $iterations evaluations (no binding modifications): $contextCount open contexts, " +
            "memory growth: ${"%.2f".format(memoryGrowthMB)} MB " +
            "(baseline: ${"%.2f".format(baselineMemory / (1024.0 * 1024.0))} MB, " +
            "final: ${"%.2f".format(finalMemory / (1024.0 * 1024.0))} MB)"
        )

        // Contexts should be automatically closed since we didn't modify output/maestro
        assertThat(contextCount).isEqualTo(0)
    }

    @Test
    fun `contexts are preserved when shared bindings are modified`() {
        // When a script modifies shared bindings (output, maestro), the context must
        // be kept alive because the stored values may be Value objects tied to that context.
        // For example: output.list = [1, 2, 3] stores a JS array that remains valid
        // only while its context is open.

        val graalEngine = engine as GraalJsEngine

        // This script modifies the output binding
        graalEngine.evaluateScript("output.myValue = 'test'")

        // Context should be preserved because output was modified
        assertThat(graalEngine.openContextCount()).isEqualTo(1)

        // The value should still be accessible in subsequent evaluations
        val result = graalEngine.evaluateScript("output.myValue")
        assertThat(result.toString()).isEqualTo("test")
    }

    @Test
    fun `contexts are preserved when storing arrays in output`() {
        // Arrays stored in output are Value objects tied to their context.
        // The context must remain open for the array to be accessible.

        val graalEngine = engine as GraalJsEngine

        graalEngine.evaluateScript("output.list = [1, 2, 3]")

        // Context preserved because output was modified
        assertThat(graalEngine.openContextCount()).isEqualTo(1)

        // Array length should be accessible
        val length = graalEngine.evaluateScript("output.list.length")
        assertThat(length.toString()).isEqualTo("3")
    }

}