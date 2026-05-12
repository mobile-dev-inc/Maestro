package util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class XCRunnerCLIUtilsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `xctestLogFile returns file under provided logsDir with the runner-log naming convention`() {
        val logsDir = tempDir.toFile()
        val date = "2026-05-08_120000"

        val result = xctestLogFile(logsDir, date)

        assertThat(result).isEqualTo(File(logsDir, "xctest_runner_2026-05-08_120000.log"))
    }

    @Test
    fun `xctestLogFile creates the logsDir lazily when it does not yet exist`() {
        val notYet = File(tempDir.toFile(), "deeply/nested/dir")
        assertThat(notYet.exists()).isFalse()

        val result = xctestLogFile(notYet, "2026-05-08_120000")

        assertThat(notYet.isDirectory).isTrue()
        assertThat(result.parentFile).isEqualTo(notYet)
    }
}
