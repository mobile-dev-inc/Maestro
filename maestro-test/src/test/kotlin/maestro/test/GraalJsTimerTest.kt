package maestro.test

import com.google.common.truth.Truth.assertThat
import maestro.js.GraalJsEngine
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach

class GraalJsTimerTest {
    private lateinit var engine: GraalJsEngine

    @BeforeEach
    fun setUp() {
        engine = GraalJsEngine()
    }

    @Test
    fun `setTimeout executes callback after delay`() {
        engine.evaluateScript("""
            setTimeout(() => {
                output.executed = true;
            }, 100);
        """.trimIndent())
        engine.waitForActiveTimers()
        
        val result = engine.evaluateScript("output.executed")
        assertThat(result.asBoolean()).isTrue()
    }

    @Test
    fun `setTimeout with multiple timers executes all callbacks`() {
        engine.evaluateScript("""
            setTimeout(() => { output.first = true; }, 100);
            setTimeout(() => { output.second = true; }, 200);
        """.trimIndent())
        engine.waitForActiveTimers()
        
        val first = engine.evaluateScript("output.first")
        val second = engine.evaluateScript("output.second")
        assertThat(first.asBoolean()).isTrue()
        assertThat(second.asBoolean()).isTrue()
    }

    @Test
    fun `clearTimeout cancels timer execution`() {
        engine.evaluateScript("""
            output.executed = false;
            const id = setTimeout(() => {
                output.executed = true;
            }, 1000);
            clearTimeout(id);
        """.trimIndent())
        engine.waitForActiveTimers()
        
        val result = engine.evaluateScript("output.executed")
        assertThat(result.asBoolean()).isFalse()
    }

    @Test
    fun `setTimeout passes arguments to callback`() {
        engine.evaluateScript("""
            setTimeout((a, b) => {
                output.sum = a + b;
            }, 100, 1, 2);
        """.trimIndent())
        engine.waitForActiveTimers()
        
        val result = engine.evaluateScript("output.sum")
        assertThat(result.asInt()).isEqualTo(3)
    }

    @Test
    fun `setTimeout actually waits for specified duration`() {
        val startTime = System.currentTimeMillis()
        engine.evaluateScript("""
            setTimeout(() => {
                output.done = true;
            }, 5000);
        """.trimIndent())
        engine.waitForActiveTimers()
        val endTime = System.currentTimeMillis()
        val elapsedTime = endTime - startTime

        val result = engine.evaluateScript("output.done")
        assertThat(result.asBoolean()).isTrue()
        assertThat(elapsedTime).isAtLeast(5000L)
    }
} 