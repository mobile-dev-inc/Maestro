package maestro.utils

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

internal class FileUtilsTest {

    @Test
    internal fun `zipDir does not throw or create output when source directory is missing`(@TempDir tempDir: Path) {
        // Given a source directory that does not exist
        val missingSource = tempDir.resolve("does-not-exist")
        val output = tempDir.resolve("out.zip")

        // When zipping it
        FileUtils.zipDir(missingSource, output)

        // Then it neither throws nor leaves an output file behind
        assertThat(output.toFile().exists()).isFalse()
    }

    @Test
    internal fun `zipDir zips files in source directory`(@TempDir tempDir: Path) {
        // Given a populated source directory
        val source = tempDir.resolve("logs").toFile().apply { mkdirs() }
        File(source, "maestro.log").writeText("hello")
        val output = tempDir.resolve("out.zip")

        // When zipping it
        FileUtils.zipDir(source.toPath(), output)

        // Then the output zip exists and contains the file
        val outFile = output.toFile()
        assertThat(outFile.exists()).isTrue()
        val unzipTarget = tempDir.resolve("unzipped")
        FileUtils.unzip(output, unzipTarget)
        assertThat(unzipTarget.resolve("maestro.log").toFile().readText()).isEqualTo("hello")
    }
}
