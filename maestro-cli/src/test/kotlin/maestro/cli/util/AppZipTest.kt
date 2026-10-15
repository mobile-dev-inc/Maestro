package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import com.hominux.compress4j.archivers.zip.ZipArchiveExtractor
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.isExecutable
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readSymbolicLink
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

    @Test
    fun `keeps the executable bit of the app binary`() {
        assumeTrue("posix" in FileSystems.getDefault().supportedFileAttributeViews())
        val bundle = tmp.resolve("My.app").createDirectories()
        val binary = bundle.resolve("My").also { it.writeText("#!/bin/sh") }
        binary.toFile().setExecutable(true)
        val out = tmp.resolve("out").createDirectories()

        val zip = zipApp(bundle.toFile(), out)

        val unpacked = unzip(zip.toPath())
        assertThat(unpacked.resolve("My.app/My").isExecutable()).isTrue()
    }

    @Test
    fun `stores a symlink inside the bundle as a symlink`() {
        val bundle = tmp.resolve("My.app").createDirectories()
        val versions = bundle.resolve("Frameworks/F.framework/Versions").createDirectories()
        versions.resolve("A").createDirectories().resolve("lib").writeText("lib")
        versions.resolve("Current").createSymbolicLinkPointingTo(Paths.get("A"))
        val out = tmp.resolve("out").createDirectories()

        val zip = zipApp(bundle.toFile(), out)

        val current = unzip(zip.toPath()).resolve("My.app/Frameworks/F.framework/Versions/Current")
        assertThat(current.isSymbolicLink()).isTrue()
        assertThat(current.readSymbolicLink()).isEqualTo(Paths.get("A"))
        assertThat(entryNames(zip.toPath())).containsNoDuplicates()
    }

    @Test
    fun `does not upload a file outside the bundle that a symlink points to`() {
        tmp.resolve("secret.txt").writeText("secret")
        val bundle = tmp.resolve("My.app").createDirectories()
        bundle.resolve("escape").createSymbolicLinkPointingTo(Paths.get("../secret.txt"))
        val out = tmp.resolve("out").createDirectories()

        val zip = zipApp(bundle.toFile(), out)

        ZipFile(zip).use { file ->
            val contents = file.entries().toList().map { file.getInputStream(it).readBytes().decodeToString() }
            assertThat(contents).doesNotContain("secret")
        }
        assertThat(entryNames(zip.toPath())).containsExactly("My.app/", "My.app/escape")
    }

    private fun entryNames(zip: Path): List<String> =
        ZipFile(zip.toFile()).use { file -> file.entries().toList().map { it.name } }

    private fun unzip(zip: Path): Path {
        val dest = Files.createTempDirectory(tmp, "unzipped")
        ZipArchiveExtractor.builder(zip).build().use { it.extract(dest) }
        return dest
    }
}
