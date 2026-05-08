package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.cli.CliError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import picocli.CommandLine

class DirectDeviceCommandParsingTest {

    @Test
    fun `launch app parses positional app id`() {
        val commandObj = LaunchAppCommand()
        val command = CommandLine(commandObj)

        command.parseArgs("com.example.app")

        assertThat(readField<String?>(commandObj, "appIdArg")).isEqualTo("com.example.app")
    }

    @Test
    fun `launch app parses option app id`() {
        val commandObj = LaunchAppCommand()
        val command = CommandLine(commandObj)

        command.parseArgs("--app-id", "com.example.app")

        assertThat(readField<String?>(commandObj, "appIdOption")).isEqualTo("com.example.app")
    }

    @Test
    fun `launch app fails without app id`() {
        val ex = assertThrows<CliError> {
            LaunchAppCommand().call()
        }

        assertThat(ex.message).contains("App ID is required")
    }

    @Test
    fun `stop app parses positional app id`() {
        val commandObj = StopAppCommand()
        val command = CommandLine(commandObj)

        command.parseArgs("com.example.app")

        assertThat(readField<String?>(commandObj, "appIdArg")).isEqualTo("com.example.app")
    }

    @Test
    fun `stop app parses option app id`() {
        val commandObj = StopAppCommand()
        val command = CommandLine(commandObj)

        command.parseArgs("--app-id", "com.example.app")

        assertThat(readField<String?>(commandObj, "appIdOption")).isEqualTo("com.example.app")
    }

    @Test
    fun `stop app fails without app id`() {
        val ex = assertThrows<CliError> {
            StopAppCommand().call()
        }

        assertThat(ex.message).contains("App ID is required")
    }

    @Test
    fun `input text parses positional text`() {
        val commandObj = InputTextCommand()
        val command = CommandLine(commandObj)

        command.parseArgs("hello world")

        assertThat(readField<String?>(commandObj, "textArg")).isEqualTo("hello world")
    }

    @Test
    fun `input text parses text option`() {
        val commandObj = InputTextCommand()
        val command = CommandLine(commandObj)

        command.parseArgs("--text", "hello world")

        assertThat(readField<String?>(commandObj, "textOption")).isEqualTo("hello world")
    }

    @Test
    fun `input text fails without text`() {
        val ex = assertThrows<CliError> {
            InputTextCommand().call()
        }

        assertThat(ex.message).contains("Text is required")
    }

    @Test
    fun `back command accepts no args`() {
        val command = CommandLine(BackCommand())

        command.parseArgs()
    }

    private inline fun <reified T> readField(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
        return field.get(target) as T
    }
}
