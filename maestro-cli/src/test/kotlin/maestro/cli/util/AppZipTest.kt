package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import com.hominux.compress4j.archivers.zip.ZipArchiveExtractor
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

class AppZipTest {

    @TempDir
    lateinit var tmp: Path

    @Test
    fun `zips a single file under its own name`() {
        val apk = tmp.resolve("app.apk").also { it.writeText("apk") }
        val out = tmp.resolve("out").createDirectories()

        val zip = zipApp(apk.toFile(), out)

        assertThat(zip.name).isEqualTo("app.apk.zip")
        val unpacked = unzip(zip.toPath())
        assertThat(unpacked.resolve("app.apk").readText()).isEqualTo("apk")
    }

    @Test
    fun `zips an app bundle directory keeping its name as the top level`() {
        val bundle = tmp.resolve("My.app").createDirectories()
        bundle.resolve("Info.plist").writeText("plist")
        bundle.resolve("Frameworks").createDirectories().resolve("a.txt").writeText("a")
        val out = tmp.resolve("out").createDirectories()

        val zip = zipApp(bundle.toFile(), out)

        val unpacked = unzip(zip.toPath())
        assertThat(unpacked.resolve("My.app/Info.plist").readText()).isEqualTo("plist")
        assertThat(unpacked.resolve("My.app/Frameworks/a.txt").readText()).isEqualTo("a")
    }

    private fun unzip(zip: Path): Path {
        val dest = Files.createTempDirectory(tmp, "unzipped")
        ZipArchiveExtractor.builder(zip).build().use { it.extract(dest) }
        return dest
    }
}
