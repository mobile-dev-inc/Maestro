import maestro.utils.parseCommand
import maestro.utils.escapeShellOutput
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShellTest {

    @Test
    fun `parseCommand returns empty list for null or blank input`() {
        assertEquals(emptyList<String>(), parseCommand(null))
        assertEquals(emptyList<String>(), parseCommand(""))
        assertEquals(emptyList<String>(), parseCommand("   "))
    }

    @Test
    fun `parseCommand parses simple space-separated command`() {
        assertEquals(listOf("echo", "hello", "world"), parseCommand("echo hello world"))
    }

    @Test
    fun `parseCommand handles double quotes`() {
        assertEquals(listOf("echo", "hello world"), parseCommand("echo \"hello world\""))
    }

    @Test
    fun `parseCommand handles single quotes`() {
        assertEquals(listOf("echo", "hello world"), parseCommand("echo 'hello world'"))
    }

    @Test
    fun `parseCommand handles escaped spaces and quotes`() {
        assertEquals(listOf("echo", "hello world"), parseCommand("echo hello\\ world"))
        assertEquals(listOf("echo", "hello\"world"), parseCommand("echo hello\\\"world"))
        assertEquals(listOf("echo", "hello'world"), parseCommand("echo hello\\'world"))
    }

    @Test
    fun `parseCommand handles mixed quotes and escapes`() {
        assertEquals(listOf("cmd", "a b", "c"), parseCommand("cmd \"a b\" c"))
        assertEquals(listOf("cmd", "a b", "c"), parseCommand("cmd 'a b' c"))
        assertEquals(listOf("cmd", "a b", "c d"), parseCommand("cmd \"a b\" 'c d'"))
        assertEquals(listOf("cmd", "a b", "c d"), parseCommand("cmd 'a b' \"c d\""))
    }

    @Test
    fun `escapeShellOutput escapes backslashes and double quotes`() {
        assertEquals("foo\\\\bar", escapeShellOutput("foo\\bar"))
        assertEquals("\\\"quoted\\\"", escapeShellOutput("\"quoted\""))
    }

    @Test
    fun `escapeShellOutput converts newlines and removes carriage returns`() {
        assertEquals("foo\\nbar", escapeShellOutput("foo\nbar"))
        assertEquals("foo\\nbar", escapeShellOutput("foo\r\nbar"))
        assertEquals("foo\\nbar", escapeShellOutput("foo\rbar\n"))
    }

    @Test
    fun `escapeShellOutput trims trailing whitespace`() {
        assertEquals("foo", escapeShellOutput("foo   "))
        assertEquals("foo\\nbar", escapeShellOutput("foo\nbar   "))
    }

    @Test
    fun `escapeShellOutput handles combined cases`() {
        val input = "  \"foo\\bar\"\r\nbaz  "
        val expected = "\\\"foo\\\\bar\\\"\\nbaz"
        assertEquals(expected, escapeShellOutput(input))
    }
}