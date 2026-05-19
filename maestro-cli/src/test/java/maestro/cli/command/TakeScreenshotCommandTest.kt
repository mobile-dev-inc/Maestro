package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.cli.CliError
import maestro.cli.devicecontrol.DirectDeviceCommandSupport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.io.path.createTempDirectory

class TakeScreenshotCommandTest {

    @Test
    fun `output parent directory validation example path is creatable`() {
        val base = File(createTempDirectory(prefix = "maestro-screenshot-test-").toString())
        val nested = File(base, "nested/path/out.png")

        val parent = nested.absoluteFile.parentFile
        DirectDeviceCommandSupport.ensureParentDirectoryExists(nested)

        assertThat(parent.isDirectory).isTrue()
    }

    @Test
    fun `output parent path cannot be a file`() {
        val base = File(createTempDirectory(prefix = "maestro-screenshot-test-").toString())
        val parentFile = File(base, "not-a-directory").apply { writeText("already a file") }
        val output = File(parentFile, "out.png")

        val error = assertThrows<CliError> {
            DirectDeviceCommandSupport.ensureParentDirectoryExists(output)
        }

        assertThat(error.message).contains("not a directory")
    }
}
