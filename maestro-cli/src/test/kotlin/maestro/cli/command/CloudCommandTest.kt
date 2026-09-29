package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
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
}
