package maestro.cli

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import picocli.CommandLine

class CommandRunNameTest {

    private fun commandRunName(vararg args: String): String =
        commandRunName(CommandLine(App()).parseArgs(*args))

    /** The name reported for arguments picocli rejects, as the parse error handler reports it. */
    private fun commandRunNameOnParseError(vararg args: String): String {
        val error = assertThrows<CommandLine.ParameterException> { CommandLine(App()).parseArgs(*args) }
        return commandRunName(error.commandLine)
    }

    @Test
    fun `names the subcommand`() {
        assertThat(commandRunName("test", "flow.yaml")).isEqualTo("test")
    }

    @Test
    fun `names the subcommand after global options`() {
        assertThat(commandRunName("--device", "emulator-5554", "record", "flow.yaml")).isEqualTo("record")
        assertThat(commandRunName("-p", "android", "--verbose", "test", "flow.yaml")).isEqualTo("test")
    }

    @Test
    fun `names the subcommand when a global option uses an equals sign`() {
        assertThat(commandRunName("--device=emulator-5554", "test", "flow.yaml")).isEqualTo("test")
    }

    @Test
    fun `names the subcommand when asking for its help`() {
        assertThat(commandRunName("test", "--help")).isEqualTo("test")
    }

    @Test
    fun `is maestro without a subcommand`() {
        assertThat(commandRunName("--version")).isEqualTo("maestro")
        assertThat(commandRunName("--help")).isEqualTo("maestro")
        assertThat(commandRunName()).isEqualTo("maestro")
    }

    @Test
    fun `names the subcommand whose arguments fail to parse`() {
        assertThat(commandRunNameOnParseError("test", "--bogus", "flow.yaml")).isEqualTo("test")
        assertThat(commandRunNameOnParseError("--device", "x", "record", "--bogus", "flow.yaml")).isEqualTo("record")
    }

    @Test
    fun `is maestro when the subcommand is unknown`() {
        assertThat(commandRunNameOnParseError("tset", "flow.yaml")).isEqualTo("maestro")
    }
}
