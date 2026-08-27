package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.device.SystemImageTag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import picocli.CommandLine

class CloudCommandTest {

    /** Reads a private field picocli populated, without running the command. */
    private fun parsedTag(vararg args: String): SystemImageTag? {
        val command = CloudCommand()
        CommandLine(command).parseArgs(*args)
        val field = CloudCommand::class.java.getDeclaredField("androidSystemImage")
        field.isAccessible = true
        return field.get(command) as SystemImageTag?
    }

    @Test
    fun `an unset flag leaves the tag null so the payload stays unchanged`() {
        assertThat(parsedTag("flow.yaml")).isNull()
    }

    @Test
    fun `the playstore value parses to the enum`() {
        assertThat(parsedTag("flow.yaml", "--android-system-image-tag", "google_apis_playstore"))
            .isEqualTo(SystemImageTag.GOOGLE_APIS_PLAYSTORE)
    }

    @Test
    fun `the google_apis value parses to the enum`() {
        assertThat(parsedTag("flow.yaml", "--android-system-image-tag", "google_apis"))
            .isEqualTo(SystemImageTag.GOOGLE_APIS)
    }

    @Test
    fun `picocli rejects a value outside the closed set`() {
        val error = assertThrows<CommandLine.ParameterException> {
            parsedTag("flow.yaml", "--android-system-image-tag", "aosp")
        }

        assertThat(error).hasMessageThat().contains("aosp")
    }

    @Test
    fun `help output lists the candidate tags`() {
        val help = CommandLine(CloudCommand()).usageMessage

        assertThat(help).contains("--android-system-image-tag")
        assertThat(help).contains("google_apis_playstore")
    }
}
