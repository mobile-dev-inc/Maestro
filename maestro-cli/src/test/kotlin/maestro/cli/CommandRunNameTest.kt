package maestro.cli

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine

class CommandRunNameTest {

    private fun commandRunName(vararg args: String): String? =
        commandRunName(CommandLine(App()).parseArgs(*args))

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
    fun `is null without a subcommand`() {
        assertThat(commandRunName("--version")).isNull()
        assertThat(commandRunName()).isNull()
    }
}
