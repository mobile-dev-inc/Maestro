package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.cli.devicecontrol.DirectDeviceCommandSupport
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
    fun `launch app reports missing app id as parameter error`() {
        val ex = assertThrows<CommandLine.ParameterException> {
            DirectDeviceCommandSupport.resolveRequiredValue(
                optionValue = null,
                argumentValue = null,
                valueName = "App ID",
                optionName = "--app-id",
                commandLine = CommandLine(LaunchAppCommand()),
            )
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
    fun `stop app reports missing app id as parameter error`() {
        val ex = assertThrows<CommandLine.ParameterException> {
            DirectDeviceCommandSupport.resolveRequiredValue(
                optionValue = null,
                argumentValue = null,
                valueName = "App ID",
                optionName = "--app-id",
                commandLine = CommandLine(StopAppCommand()),
            )
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
    fun `input text reports missing text as parameter error`() {
        val ex = assertThrows<CommandLine.ParameterException> {
            DirectDeviceCommandSupport.resolveRequiredValue(
                optionValue = null,
                argumentValue = null,
                valueName = "Text",
                optionName = "--text",
                commandLine = CommandLine(InputTextCommand()),
            )
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
