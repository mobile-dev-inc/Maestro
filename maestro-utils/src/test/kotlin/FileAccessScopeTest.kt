import com.google.common.truth.Truth.assertThat
import maestro.utils.FileAccessScope
import maestro.utils.PathOutsideScope
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FileAccessScopeTest {

    private fun realDir(prefix: String): Path = Files.createTempDirectory(prefix).toRealPath()

    @Test
    fun `everything resolves a relative candidate against the anchor`() {
        val anchor = realDir("anchor")
        val resolved = FileAccessScope.everything.resolve(anchor, "media/image.png")
        assertThat(resolved).isEqualTo(anchor.resolve("media/image.png").toAbsolutePath().normalize())
    }

    @Test
    fun `everything returns an absolute candidate unchanged in shape`() {
        val anchor = realDir("anchor")
        val abs = realDir("elsewhere").resolve("file.txt")
        val resolved = FileAccessScope.everything.resolve(anchor, abs.toString())
        assertThat(resolved.isAbsolute).isTrue()
        assertThat(resolved).isEqualTo(abs)
    }

    @Test
    fun `everything stays on the anchor's filesystem provider`() {
        val anchor = realDir("anchor")
        val resolved = FileAccessScope.everything.resolve(anchor, "sub/file.txt")
        assertThat(resolved.fileSystem).isEqualTo(anchor.fileSystem)
    }

    @Test
    fun `under allows an in-workspace relative path`() {
        val base = realDir("workspace")
        Files.createDirectories(base.resolve("media"))
        Files.writeString(base.resolve("media/image.png"), "x")
        val resolved = FileAccessScope.under(base).resolve(base, "media/image.png")
        assertThat(resolved).isEqualTo(base.resolve("media/image.png").toRealPath())
    }

    @Test
    fun `under allows a sibling directory that is still under the root`() {
        val base = realDir("workspace")
        val flows = Files.createDirectories(base.resolve("flows"))
        Files.createDirectories(base.resolve("media"))
        Files.writeString(base.resolve("media/image.png"), "x")
        // anchor is a subdir; ../media/image.png climbs one level but stays under base
        val resolved = FileAccessScope.under(base).resolve(flows, "../media/image.png")
        assertThat(resolved).isEqualTo(base.resolve("media/image.png").toRealPath())
    }

    @Test
    fun `under rejects an absolute path outside the root`() {
        val base = realDir("workspace")
        val outside = realDir("outside").resolve("secret.txt")
        Files.writeString(outside, "x")
        assertThrows(PathOutsideScope::class.java) {
            FileAccessScope.under(base).resolve(base, outside.toString())
        }
    }

    @Test
    fun `under rejects a parent-directory climb out of the root`() {
        val base = realDir("workspace")
        assertThrows(PathOutsideScope::class.java) {
            FileAccessScope.under(base).resolve(base, "../../etc/hosts")
        }
    }

    @Test
    fun `under rejects a symlink inside the root that points outside`() {
        val base = realDir("workspace")
        val outsideDir = realDir("outside")
        Files.writeString(outsideDir.resolve("secret.txt"), "x")
        val link = base.resolve("link")
        Files.createSymbolicLink(link, outsideDir)
        assertThrows(PathOutsideScope::class.java) {
            FileAccessScope.under(base).resolve(base, "link/secret.txt")
        }
    }

    @Test
    fun `under confines a not-yet-created path via its nearest existing ancestor`() {
        val base = realDir("workspace")
        // File does not exist yet; must still resolve (and be allowed) under base.
        val resolved = FileAccessScope.under(base).resolve(base, "screenshots/new.png")
        assertThat(resolved.startsWith(base.toRealPath())).isTrue()
    }

    @Test
    fun `under rejects a not-yet-created path whose ancestor escapes the root`() {
        val base = realDir("workspace")
        assertThrows(PathOutsideScope::class.java) {
            FileAccessScope.under(base).resolve(base, "../sibling/new.png")
        }
    }
}
