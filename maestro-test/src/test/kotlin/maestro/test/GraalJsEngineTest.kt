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
    fun `memory should remain bounded after many evaluations without binding modifications`() {
        // This test simulates a repeat loop with a condition like:
        //   - repeat:
        //       while:
        //         notVisible: '${MARKET}'
        //       commands:
        //         - swipe: ...
        //
        // The ${MARKET} expression is evaluated on every iteration.
        //
        // PROBLEM: If each evaluateScript() creates a new GraalJS context that is
        // never released until close() is called, this causes:
        //   - 100 evaluations = 100 open contexts
        //   - ~68 MB memory growth for just 100 simple evaluations (~0.68 MB each)
        //   - OOM errors for flows with 1000+ iterations
        //
        // SOLUTION: Either close contexts after evaluation (PR #2881 approach) or
        // reuse a single context with IIFE isolation (alternative approach).

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

        // Log the results for visibility
        println(
            "After $iterations evaluations (no binding modifications): " +
            "memory growth: ${"%.2f".format(memoryGrowthMB)} MB " +
            "(baseline: ${"%.2f".format(baselineMemory / (1024.0 * 1024.0))} MB, " +
            "final: ${"%.2f".format(finalMemory / (1024.0 * 1024.0))} MB)"
        )

        // Memory growth should be minimal - well under what 100 contexts would consume
        // 100 contexts at ~0.68 MB each would be ~68 MB
        // With proper isolation (single context or context closing), growth should be < 10 MB
        assertThat(memoryGrowthMB).isLessThan(10.0)
    }

    @Test
    fun `output bindings should persist across evaluations`() {
        // When a script modifies shared bindings (output), the values must
        // remain accessible in subsequent evaluations.

        val graalEngine = engine as GraalJsEngine

        // This script modifies the output binding
        graalEngine.evaluateScript("output.myValue = 'test'")

        // The value should still be accessible in subsequent evaluations
        val result = graalEngine.evaluateScript("output.myValue")
        assertThat(result.toString()).isEqualTo("test")
    }

    @Test
    fun `arrays stored in output should remain accessible`() {
        // Arrays stored in output must remain accessible across evaluations.

        val graalEngine = engine as GraalJsEngine

        graalEngine.evaluateScript("output.list = [1, 2, 3]")

        // Array length should be accessible
        val length = graalEngine.evaluateScript("output.list.length")
        assertThat(length.toString()).isEqualTo("3")

        // Array elements should be accessible
        val firstElement = graalEngine.evaluateScript("output.list[0]")
        assertThat(firstElement.toString()).isEqualTo("1")
    }

    @Test
    fun `objects stored in output should remain accessible`() {
        // Objects stored in output must remain accessible across evaluations.

        val graalEngine = engine as GraalJsEngine

        graalEngine.evaluateScript("output.user = { name: 'Alice', age: 30 }")

        // Object properties should be accessible
        val name = graalEngine.evaluateScript("output.user.name")
        assertThat(name.toString()).isEqualTo("Alice")

        val age = graalEngine.evaluateScript("output.user.age")
        assertThat(age.toString()).isEqualTo("30")
    }

}