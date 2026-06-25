package maestro.orchestra.debug

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.ArtifactFormat
import maestro.orchestra.ArtifactKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ArtifactCollectorTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `allocate creates parent dirs and returns a file under the run root`() {
        val collector = ArtifactCollector(tempDir)

        val file = collector.allocate(
            ArtifactKind.SCREEN_HIERARCHY,
            ArtifactFormat.JSON,
            "screen-hierarchy/step-0.json",
        )
        file.writeText("{}")

        assertThat(file.exists()).isTrue()
        assertThat(file.toPath().startsWith(tempDir)).isTrue()
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
