import maestro.utils.CommandLine.escapeCommandLineOutput
import maestro.utils.CommandLine.parseCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommandLineEdgeCasesTest {

    @Test
    fun `parseCommand handles unclosed double quote`() {
        assertEquals(listOf("foo", "bar baz"), parseCommand("foo \"bar baz"))
    }

    @Test
    fun `parseCommand handles unclosed single quote`() {
        assertEquals(listOf("foo", "bar baz"), parseCommand("foo 'bar baz"))
    }

    @Test
    fun `parseCommand handles escaped backslash at end`() {
        assertEquals(listOf("echo", "foo\\"), parseCommand("echo foo\\\\"))
    }

    @Test
    fun `parseCommand handles multiple consecutive spaces`() {
        assertEquals(listOf("foo", "bar", "baz"), parseCommand("foo   bar    baz"))
    }

    @Test
    fun `parseCommand handles arguments with only quotes`() {
        assertEquals(listOf("foo", ""), parseCommand("foo \"\""))
        assertEquals(listOf("foo", ""), parseCommand("foo ''"))
    }

    @Test
    fun `parseCommand handles escaped whitespace inside quotes`() {
        assertEquals(listOf("foo", "bar\\ baz"), parseCommand("foo \"bar\\ baz\""))
    }

    @Test
    fun `escapeCommandLineOutput handles only whitespace and newlines`() {
        assertEquals("", escapeCommandLineOutput("   \n\r\n  "))
    }

    @Test
    fun `escapeCommandLineOutput handles only backslashes and quotes`() {
        assertEquals("\\\\\\\"\\\"", escapeCommandLineOutput("\\\"\""))
    }

    @Test
    fun `escapeCommandLineOutput handles mixed escaped and unescaped characters`() {
        assertEquals("foo\\\\\\\"bar\\n", escapeCommandLineOutput("foo\\\"bar\n"))
    }
}
