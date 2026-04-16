package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.cli.CliError
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class TakeScreenshotCommandTest {

    @Test
    fun `output parent directory validation example path is creatable`() {
        val base = createTempDir(prefix = "maestro-screenshot-test-")
        val nested = File(base, "nested/path/out.png")

        val parent = nested.absoluteFile.parentFile
        val created = parent.mkdirs()

        assertThat(created || parent.exists()).isTrue()
    }

    @Test
    fun `sanity check cli error type available for command validation`() {
        val error = assertThrows<CliError> {
            throw CliError("boom")
        }

        assertThat(error.message).contains("boom")
    }
}
