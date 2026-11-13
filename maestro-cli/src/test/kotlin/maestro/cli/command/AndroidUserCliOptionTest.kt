package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.cli.App
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import picocli.CommandLine

class AndroidUserCliOptionTest {

    @Test
    fun `test command accepts android user`() {
        val parsed = CommandLine(App()).parseArgs("test", "--android-user", "10", "flow.yaml")

        val test = parsed.subcommand().commandSpec().userObject() as TestCommand
        assertThat(test.androidUser).isEqualTo(10)
    }

    @Test
    fun `android user is rejected before the command`() {
        assertThrows<CommandLine.UnmatchedArgumentException> {
            CommandLine(App()).parseArgs("--android-user", "10", "test", "flow.yaml")
        }
    }

    @Test
    fun `android user is rejected by other commands`() {
        assertThrows<CommandLine.UnmatchedArgumentException> {
            CommandLine(App()).parseArgs("record", "--android-user", "10", "flow.yaml")
        }
    }
}
