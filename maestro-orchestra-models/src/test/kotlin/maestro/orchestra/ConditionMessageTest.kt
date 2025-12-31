package maestro.orchestra

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull

import org.junit.jupiter.api.Test

class ConditionMessageTest {
    @Test
    fun `AssertEqual failureMessage`() {
        val ae = AssertEqual("maestro", "maestros")
        assertEquals(ae.failureMessage(), "Assertion failed: expected 'maestros', but got 'maestro'")
    }

    @Test
    fun `AssertNotEqual failureMessage`() {
        val ane = AssertNotEqual("same", "same")
        assertEquals(ane.failureMessage(), "Assertion failed: expected values to differ, but both were 'same'")
    }

    @Test
    fun `Condition default message`() {
        val cond = Condition()
        assertEquals(cond.failureMessage(), "Assertion is false: true")
    }

    @Test
    fun `Condition prefers label when present`() {
        val cond = Condition(label = "Custom label", equal = AssertEqual("a", "b"))
        assertEquals(cond.failureMessage(), "Custom label")
    }

    @Test
    fun `Condition delegates to equal`() {
        val cond = Condition(equal = AssertEqual("foo", "bar"))
        assertEquals(cond.failureMessage(), "Assertion failed: expected 'bar', but got 'foo'")
    }

    @Test
    fun `Condition delegates to notEqual`() {
        val cond = Condition(notEqual = AssertNotEqual("x", "x"))
        assertEquals(cond.failureMessage(), "Assertion failed: expected values to differ, but both were 'x'")
    }
}
