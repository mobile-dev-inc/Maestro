package maestro.orchestra.debug

import com.google.common.truth.Truth.assertThat
import maestro.MaestroException
import maestro.orchestra.ArtifactFormat
import maestro.orchestra.ArtifactKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.nio.file.Path

class ArtifactCollectorTest {

    @TempDir
    lateinit var tempDir: Path

    /** The collector canonicalizes its folder, so returned files sit under the resolved temp dir. */
    private val realTempDir: Path get() = tempDir.toRealPath()

    @Test
    fun `allocate creates parent dirs and returns a file under the artifacts folder`() {
        val collector = ArtifactCollector(tempDir)

        val file = collector.allocate(
            ArtifactKind.SCREEN_HIERARCHY,
            ArtifactFormat.JSON,
            "screen-hierarchy/step-0.json",
        )
        file.writeText("{}")

        assertThat(file.exists()).isTrue()
        assertThat(file.toPath().startsWith(realTempDir)).isTrue()
    }

    @Test
    fun `manifest folds a collection kind into one folder entry with a count`() {
        val collector = ArtifactCollector(tempDir)

        collector.allocate(ArtifactKind.SCREENSHOT, ArtifactFormat.PNG, "screenshots/step-0.png").writeText("a")
        collector.allocate(ArtifactKind.SCREENSHOT, ArtifactFormat.PNG, "screenshots/step-1.png").writeText("b")

        val entry = collector.manifest().entries.single { it.kind == ArtifactKind.SCREENSHOT }
        assertThat(entry.relativePath).isEqualTo("screenshots")
        assertThat(entry.count).isEqualTo(2)
        assertThat(entry.sizeBytes).isNull()
    }

    @Test
    fun `manifest emits a single-file entry with sizeBytes for non-collection kinds`() {
        val collector = ArtifactCollector(tempDir)

        collector.allocate(ArtifactKind.MAESTRO_LOG, ArtifactFormat.TXT, "logs/maestro.log").writeText("hello")

        val entry = collector.manifest().entries.single { it.kind == ArtifactKind.MAESTRO_LOG }
        assertThat(entry.relativePath).isEqualTo("logs/maestro.log")
        assertThat(entry.count).isNull()
        assertThat(entry.sizeBytes).isEqualTo(5L)
    }

    @Test
    fun `records whose file was never written are dropped from the manifest`() {
        val collector = ArtifactCollector(tempDir)

        // Allocated but never written (e.g. the capture threw mid-write).
        collector.allocate(ArtifactKind.SCREENSHOT, ArtifactFormat.PNG, "screenshots/step-0.png")

        assertThat(collector.manifest().entries.none { it.kind == ArtifactKind.SCREENSHOT }).isTrue()
    }

    @Test
    fun `adopt records an externally-produced file with its metadata`() {
        val collector = ArtifactCollector(tempDir)
        tempDir.resolve("logs").toFile().mkdirs()
        tempDir.resolve("logs/device-logcat.txt").toFile().writeText("logcat")

        collector.adopt(
            ArtifactKind.DEVICE_LOG,
            "logs/device-logcat.txt",
            ArtifactFormat.TXT,
            metadata = mapOf("source" to "emulator"),
        )

        val entry = collector.manifest().entries.single { it.kind == ArtifactKind.DEVICE_LOG }
        assertThat(entry.relativePath).isEqualTo("logs/device-logcat.txt")
        assertThat(entry.metadata["source"]).isEqualTo("emulator")
        assertThat(entry.format).isEqualTo(ArtifactFormat.TXT)
    }

    @Test
    fun `allocate normalizes the path so the dirs it creates are the ones the write opens`() {
        val collector = ArtifactCollector(tempDir)

        val file = collector.allocate(
            ArtifactKind.START_SCREEN_RECORDING,
            ArtifactFormat.MP4,
            "startRecording/../logs/screenshots/clip.mp4",
        )
        file.writeText("mp4")

        assertThat(file.toPath()).isEqualTo(realTempDir.resolve("logs/screenshots/clip.mp4"))
        assertThat(file.exists()).isTrue()
    }

    @Test
    fun `manifest reports the normalized path, so lookups find the file that was written`() {
        val collector = ArtifactCollector(tempDir)

        collector.allocate(ArtifactKind.MAESTRO_LOG, ArtifactFormat.TXT, "logs/./maestro.log").writeText("hello")

        val entry = collector.manifest().entries.single { it.kind == ArtifactKind.MAESTRO_LOG }
        assertThat(entry.relativePath).isEqualTo("logs/maestro.log")
        assertThat(entry.sizeBytes).isEqualTo(5L)
    }

    @ParameterizedTest
    @ValueSource(strings = ["startRecording/../../clip.mp4", "/tmp/clip.mp4"])
    fun `allocate refuses a path that escapes the artifacts folder`(escaping: String) {
        assertThrows<IllegalArgumentException> {
            ArtifactCollector(tempDir).allocate(ArtifactKind.START_SCREEN_RECORDING, ArtifactFormat.MP4, escaping)
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["clip", "login/home", "login/../home", "/tmp/clip"])
    fun `validateCommandPath accepts any path that names a file`(path: String) {
        ArtifactCollector.validateCommandPath(path, "startRecording")
    }

    @Test
    fun `validateCommandPath rejects a blank path`() {
        val e = assertThrows<MaestroException.InvalidCommand> {
            ArtifactCollector.validateCommandPath("", "startRecording")
        }

        assertThat(e.message).contains("startRecording")
        assertThat(e.message).contains("empty")
    }

    @ParameterizedTest
    @ValueSource(strings = ["logs/screenshots/", "logs\\screenshots\\"])
    fun `validateCommandPath rejects a path that names no file`(path: String) {
        val e = assertThrows<MaestroException.InvalidCommand> {
            ArtifactCollector.validateCommandPath(path, "startRecording")
        }

        assertThat(e.message).contains("file name")
    }

    @Test
    fun `allocateCommandOutput keeps a path that walks back within the command folder`() {
        val file = ArtifactCollector(tempDir)
            .allocateCommandOutput(ArtifactKind.START_SCREEN_RECORDING, "login/../clip.mp4", "startRecording", null)

        assertThat(file.toPath()).isEqualTo(realTempDir.resolve("startRecording/clip.mp4"))
    }

    @Test
    fun `allocateCommandOutput accepts an absolute path that lands in the command folder`() {
        val absolute = tempDir.resolve("startRecording/clip.mp4").toString()

        val file = ArtifactCollector(tempDir)
            .allocateCommandOutput(ArtifactKind.START_SCREEN_RECORDING, absolute, "startRecording", null)

        assertThat(file.toPath()).isEqualTo(realTempDir.resolve("startRecording/clip.mp4"))
    }

    @Test
    fun `allocateCommandOutput records an absolute path relative to the artifacts folder`() {
        val collector = ArtifactCollector(tempDir)
        val absolute = tempDir.resolve("startRecording/clip.mp4").toString()

        collector.allocateCommandOutput(ArtifactKind.START_SCREEN_RECORDING, absolute, "startRecording", 1)
            .writeText("mp4")

        assertThat(collector.artifactsForStep(1).single().path).isEqualTo("startRecording/clip.mp4")
    }

    @ParameterizedTest
    @ValueSource(strings = ["../clip.mp4", "login/../../clip.mp4", "/tmp/clip.mp4", "login/.."])
    fun `allocateCommandOutput rejects a path that lands outside the command folder as a flow error`(escaping: String) {
        val e = assertThrows<MaestroException.InvalidCommand> {
            ArtifactCollector(tempDir)
                .allocateCommandOutput(ArtifactKind.START_SCREEN_RECORDING, escaping, "startRecording", null)
        }

        assertThat(e.message).contains("startRecording")
        assertThat(e.message).contains("resolves outside")
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `allocateCommandOutput treats a backslash as a file-name character, not an escape`() {
        val file = ArtifactCollector(tempDir)
            .allocateCommandOutput(ArtifactKind.START_SCREEN_RECORDING, "..\\clip.mp4", "startRecording", null)

        assertThat(file.toPath()).isEqualTo(realTempDir.resolve("startRecording/..\\clip.mp4"))
    }

    @Test
    fun `allocateCommandOutput accepts a plain name when the artifacts dir carries a dot segment`() {
        val dotted = tempDir.resolve(".").resolve("art")

        val file = ArtifactCollector(dotted)
            .allocateCommandOutput(ArtifactKind.START_SCREEN_RECORDING, "clip.mp4", "startRecording", null)

        assertThat(file.toPath()).isEqualTo(realTempDir.resolve("art/startRecording/clip.mp4"))
    }

    @Test
    fun `allocateCommandOutput accepts an absolute path given through the artifacts dir's real path`() {
        val collector = ArtifactCollector(tempDir)
        val viaRealPath = tempDir.toRealPath().resolve("startRecording/clip.mp4").toString()

        collector.allocateCommandOutput(ArtifactKind.START_SCREEN_RECORDING, viaRealPath, "startRecording", 1)
            .writeText("mp4")

        assertThat(collector.artifactsForStep(1).single().path).isEqualTo("startRecording/clip.mp4")
    }

    @Test
    fun `allocateCommandOutput rejects a path the filesystem cannot represent as a flow error`() {
        val e = assertThrows<MaestroException.InvalidCommand> {
            ArtifactCollector(tempDir)
                .allocateCommandOutput(ArtifactKind.START_SCREEN_RECORDING, "a\u0000b.mp4", "startRecording", null)
        }

        assertThat(e.message).contains("startRecording")
    }

    @Test
    fun `a path overwritten across loop iterations counts once, not per record`() {
        val collector = ArtifactCollector(tempDir)
        tempDir.resolve("takeScreenshot").toFile().mkdirs()
        tempDir.resolve("takeScreenshot/shot.png").toFile().writeText("x")

        // Same command path re-run twice (repeat loop) overwrites the one file.
        collector.adopt(ArtifactKind.TAKE_SCREENSHOT, "takeScreenshot/shot.png", ArtifactFormat.PNG)
        collector.adopt(ArtifactKind.TAKE_SCREENSHOT, "takeScreenshot/shot.png", ArtifactFormat.PNG)

        val entry = collector.manifest().entries.single { it.kind == ArtifactKind.TAKE_SCREENSHOT }
        assertThat(entry.count).isEqualTo(1)
    }

}
