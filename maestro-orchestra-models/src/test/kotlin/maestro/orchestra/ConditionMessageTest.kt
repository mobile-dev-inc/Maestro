package maestro.orchestra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConditionMessageTest {
    @Test
    fun `EqualityCondition failureMessage via Condition`() {
        val cond = Condition(equal = EqualityCondition("maestro", "maestros"))
        assertEquals("Assertion failed: expected 'maestros' to equal 'maestro'", cond.failureMessage())
    }

    @Test
    fun `Condition default message`() {
        val cond = Condition()
        assertEquals("Assertion is false: true", cond.failureMessage())
    }

    @Test
    fun `Condition with label returns label`() {
        val cond = Condition(label = "Custom label", equal = EqualityCondition("a", "b"))
        assertEquals("Custom label", cond.failureMessage())
    }

    @Test
    fun `Condition delegates to equal for failureMessage`() {
        val cond = Condition(equal = EqualityCondition("foo", "bar"))
        assertEquals("Assertion failed: expected 'bar' to equal 'foo'", cond.failureMessage())
    }

    @Test
    fun `Condition delegates to notEqual for failureMessage`() {
        val cond = Condition(notEqual = EqualityCondition("foo", "foo"))
        assertEquals("Assertion failed: expected 'foo' to not equal 'foo'", cond.failureMessage())
    }
}
