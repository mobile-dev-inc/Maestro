package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.cli.api.UploadPriority
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import picocli.CommandLine

class CloudCommandTest {

    @Test
    fun `help output documents the full-path device-os form and drops the dedicated flag`() {
        // picocli wraps long option descriptions across lines, so assert on a fragment that
        // survives wrapping rather than the whole "system-images;..." literal.
        val help = CommandLine(CloudCommand()).usageMessage
        assertThat(help).doesNotContain("--android-system-image")
        assertThat(help).contains("Android system image")
        assertThat(help).contains("system-images;")
    }

    @Test
    fun `--priority accepts every level in any case`() {
        mapOf(
            "low" to UploadPriority.LOW,
            "NORMAL" to UploadPriority.NORMAL,
            "High" to UploadPriority.HIGH,
        ).forEach { (input, expected) ->
            val parsed = commandLine().parseArgs("--flows", "flows", "--priority", input)
            assertThat(parsed.matchedOptionValue("--priority", null as UploadPriority?)).isEqualTo(expected)
        }
    }

    @Test
    fun `--priority is optional and rejects unknown values`() {
        assertThat(commandLine().parseArgs("--flows", "flows").hasMatchedOption("--priority")).isFalse()
        assertThrows<CommandLine.ParameterException> {
            commandLine().parseArgs("--flows", "flows", "--priority", "urgent")
        }
    }

    // App.kt enables case-insensitive enums on the root command; a bare CloudCommand needs it set here.
    private fun commandLine() = CommandLine(CloudCommand()).setCaseInsensitiveEnumValuesAllowed(true)
}
