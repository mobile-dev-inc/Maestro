package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import picocli.CommandLine

class CloudCommandTest {

    @Test
    fun `help output lists the new flag`() {
        val help = CommandLine(CloudCommand()).usageMessage
        assertThat(help).contains("--android-system-image")
    }
}
