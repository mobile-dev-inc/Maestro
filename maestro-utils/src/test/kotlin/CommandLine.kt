import maestro.utils.CommandLine.escapeCommandLineOutput
import maestro.utils.CommandLine.parseCommand
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommandLineTest {

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
        assertEquals("foo\\\\bar", escapeCommandLineOutput("foo\\bar"))
        assertEquals("\\\"quoted\\\"", escapeCommandLineOutput("\"quoted\""))
    }

    @Test
    fun `escapeShellOutput converts newlines and removes carriage returns`() {
        assertEquals("foo\\nbar", escapeCommandLineOutput("foo\nbar"))
        assertEquals("foo\\nbar", escapeCommandLineOutput("foo\r\nbar"))
        assertEquals("foo\\nbar", escapeCommandLineOutput("foo\rbar\n"))
    }

    @Test
    fun `escapeShellOutput trims trailing whitespace`() {
        assertEquals("foo", escapeCommandLineOutput("foo   "))
        assertEquals("foo\\nbar", escapeCommandLineOutput("foo\nbar   "))
    }

    @Test
    fun `escapeShellOutput handles combined cases`() {
        val input = "  \"foo\\bar\"\r\nbaz  "
        val expected = "\\\"foo\\\\bar\\\"\\nbaz"
        assertEquals(expected, escapeCommandLineOutput(input))
    }
}
