package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine

class TakeScreenshotCommandParsingTest {

    @Test
    fun `output option is required`() {
        val command = CommandLine(TakeScreenshotCommand())

        val ex = org.junit.jupiter.api.assertThrows<CommandLine.MissingParameterException> {
            command.parseArgs()
        }

        assertThat(ex.message).contains("--output")
    }

    @Test
    fun `parses output option`() {
        val commandObj = TakeScreenshotCommand()
        val command = CommandLine(commandObj)

        command.parseArgs("--output", "artifacts/screen.png")

        val outputField = commandObj.javaClass.getDeclaredField("output").apply { isAccessible = true }
        val output = outputField.get(commandObj) as java.io.File

        assertThat(output.path).isEqualTo("artifacts/screen.png")
    }
}
