package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import maestro.cli.util.FileUtils.toCwdRelativeOrAbsoluteString
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Paths

class FileUtilsTest {

    @Test
    fun `path under CWD is relativised against CWD`() {
        val cwd = Paths.get("").toAbsolutePath()
        val target = cwd.resolve(".maestro").resolve("aaa").resolve("bbb.yaml")

        val expected = listOf(".maestro", "aaa", "bbb.yaml").joinToString(File.separator)
        assertThat(target.toCwdRelativeOrAbsoluteString()).isEqualTo(expected)
    }

    @Test
    fun `path outside CWD falls back to absolute`() {
        val cwd = Paths.get("").toAbsolutePath()
        val parent = cwd.parent ?: return
        val outside = parent.resolve("definitely-not-under-cwd.yaml")

        assertThat(outside.toCwdRelativeOrAbsoluteString())
            .isEqualTo(outside.toAbsolutePath().toString())
    }

    @Test
    fun `non-absolute input is absolutised before relativising`() {
        val relative = Paths.get(".maestro", "x.yaml")
        val expected = listOf(".maestro", "x.yaml").joinToString(File.separator)

        assertThat(relative.toCwdRelativeOrAbsoluteString()).isEqualTo(expected)
    }

    @Test
    fun `path equal to CWD falls back to the absolute CWD string`() {
        // relativize(self) returns an empty path; the helper guards against that
        // and falls back to the absolute path so callers never see a blank value.
        val cwd = Paths.get("").toAbsolutePath()

        assertThat(cwd.toCwdRelativeOrAbsoluteString()).isEqualTo(cwd.toString())
    }
}
