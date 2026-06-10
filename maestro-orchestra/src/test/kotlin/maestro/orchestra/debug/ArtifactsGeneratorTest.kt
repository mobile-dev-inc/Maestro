package maestro.orchestra.debug

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import maestro.Maestro
import maestro.MaestroException
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.CapturedDeviceArtifact
import maestro.device.DeviceArtifactFiles
import maestro.orchestra.ArtifactFormat
import maestro.orchestra.ArtifactKind
import maestro.orchestra.ArtifactManifest
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import okio.Buffer
import okio.Sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class ArtifactsGeneratorTest {

    @TempDir
    lateinit var tempDir: Path

    private fun mockMaestro(
        screenshotBytes: ByteArray = byteArrayOf(1, 2, 3, 4),
        hierarchyRoot: TreeNode = TreeNode(attributes = mutableMapOf("text" to "root")),
    ): Maestro = mockk(relaxed = true) {
        coEvery { takeScreenshot(any<Sink>(), any()) } answers {
            val sink = firstArg<Sink>()
            val buffer = Buffer().write(screenshotBytes)
            sink.write(buffer, buffer.size)
            sink.flush()
        }
        coEvery { viewHierarchy(any()) } returns ViewHierarchy(hierarchyRoot)
    }

    @Test
    fun `populates debugOutput in memory even when artifactsDir is null`() {
        val gen = ArtifactsGenerator(artifactsDir = null, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, startedAt = 100L, finishedAt = 150L)
        gen.onFlowEnd()

        val metadata = gen.debugOutput.commands[cmd]!!
        assertThat(metadata.status).isEqualTo(CommandStatus.COMPLETED)
        assertThat(metadata.duration).isEqualTo(50L)
        assertThat(metadata.sequenceNumber).isEqualTo(0)
    }

    @Test
    fun `writes commands_json to artifactsDir at onFlowEnd`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, startedAt = 100L, finishedAt = 150L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("commands.json").exists()).isTrue()
        val content = Files.readString(tempDir.resolve("commands.json"))
        assertThat(content).contains("\"status\" : \"COMPLETED\"")
    }

    @Test
    fun `with null artifactsDir, writes no files and produces an empty manifest`() {
        val gen = ArtifactsGenerator(artifactsDir = null, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        // No artifactsDir => nothing on disk and nothing in the manifest.
        assertThat(tempDir.toFile().listFiles()?.isEmpty()).isTrue()
        assertThat(gen.artifactManifest.entries).isEmpty()
    }

    @Test
    fun `on failure with artifactsDir, captures hierarchy and screenshot independently`() {
        val maestro = mockMaestro()
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = maestro)
        val cmd = MaestroCommand(tapOnElement = null)
        val error = RuntimeException("boom")

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(error), 100L, 200L)
        gen.onFlowEnd()

        // Hierarchy attached inline on the metadata.
        val metadata = gen.debugOutput.commands[cmd]!!
        assertThat(metadata.status).isEqualTo(CommandStatus.FAILED)
        assertThat(metadata.error).isEqualTo(error)
        assertThat(metadata.hierarchy).isNotNull()

        // Failure screenshot written as a separate file in the artifacts dir.
        val screenshots = tempDir.toFile().listFiles { _, n -> n.startsWith("screenshot-❌-") }
        assertThat(screenshots).isNotNull()
        assertThat(screenshots!!.size).isEqualTo(1)
        assertThat(gen.debugOutput.screenshots).hasSize(1)
        assertThat(gen.debugOutput.screenshots[0].status).isEqualTo(CommandStatus.FAILED)
    }

    @Test
    fun `screenshot failure does not block hierarchy capture`() {
        // Screenshot throws; hierarchy still lands on the metadata.
        val maestro: Maestro = mockk(relaxed = true) {
            coEvery { takeScreenshot(any<Sink>(), any()) } throws RuntimeException("screenshot boom")
            coEvery { viewHierarchy(any()) } returns ViewHierarchy(
                TreeNode(attributes = mutableMapOf("text" to "root"))
            )
        }
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = maestro)
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(RuntimeException("test")), 100L, 200L)
        gen.onFlowEnd()

        val metadata = gen.debugOutput.commands[cmd]!!
        assertThat(metadata.hierarchy).isNotNull()
        // No screenshot file landed (capture threw)
        val screenshots = tempDir.toFile().listFiles { _, n -> n.startsWith("screenshot-❌-") } ?: emptyArray()
        assertThat(screenshots).isEmpty()
    }

    @Test
    fun `hierarchy failure does not block screenshot capture`() {
        // Hierarchy throws; screenshot still lands.
        val maestro: Maestro = mockk(relaxed = true) {
            coEvery { takeScreenshot(any<Sink>(), any()) } answers {
                val sink = firstArg<Sink>()
                val buf = Buffer().write(byteArrayOf(1, 2, 3))
                sink.write(buf, buf.size)
                sink.flush()
            }
            coEvery { viewHierarchy(any()) } throws RuntimeException("hierarchy boom")
        }
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = maestro)
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(RuntimeException("test")), 100L, 200L)
        gen.onFlowEnd()

        val metadata = gen.debugOutput.commands[cmd]!!
        assertThat(metadata.hierarchy).isNull()
        val screenshots = tempDir.toFile().listFiles { _, n -> n.startsWith("screenshot-❌-") }
        assertThat(screenshots).isNotNull()
        assertThat(screenshots!!.size).isEqualTo(1)
    }

    @Test
    fun `MaestroException on failure populates debugOutput_exception`() {
        val gen = ArtifactsGenerator(artifactsDir = null, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)
        val mErr = MaestroException.UnableToLaunchApp("nope")

        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(mErr), 100L, 200L)

        assertThat(gen.debugOutput.exception).isEqualTo(mErr)
    }

    @Test
    fun `onCommandReset transitions status to PENDING`() {
        val gen = ArtifactsGenerator(artifactsDir = null, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 200L)
        gen.onCommandReset(cmd)

        assertThat(gen.debugOutput.commands[cmd]!!.status).isEqualTo(CommandStatus.PENDING)
    }

    @Test
    fun `manifest exposes command metadata and maestro log entries`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        val byKind = gen.artifactManifest.entries.associateBy { it.kind }
        assertThat(byKind.keys).contains(ArtifactKind.COMMAND_METADATA)
        assertThat(byKind.keys).contains(ArtifactKind.MAESTRO_LOG)

        val cmdEntry = byKind[ArtifactKind.COMMAND_METADATA]!!
        assertThat(cmdEntry.relativePath).isEqualTo("commands.json")
        assertThat(cmdEntry.format).isEqualTo(ArtifactFormat.JSON)
        assertThat(cmdEntry.sizeBytes).isGreaterThan(0L)
    }

    @Test
    fun `manifest includes a failure screenshot entry`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(RuntimeException("boom")), 100L, 200L)
        gen.onFlowEnd()

        val shots = gen.artifactManifest.entries.filter { it.kind == ArtifactKind.SCREENSHOT }
        assertThat(shots).hasSize(1)
        assertThat(shots[0].format).isEqualTo(ArtifactFormat.PNG)
        assertThat(shots[0].relativePath).startsWith("screenshot-❌-")
    }

    @Test
    fun `writes manifest_json to artifactsDir at onFlowEnd`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        val manifestFile = tempDir.resolve("manifest.json").toFile()
        assertThat(manifestFile.exists()).isTrue()

        // The on-disk manifest carries a `$schema` field, so decode the way a real
        // consumer must: tolerantly (the model intentionally has no @JsonIgnoreProperties).
        val tolerant = jacksonObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val decoded = tolerant.readValue<ArtifactManifest>(manifestFile.readText())
        assertThat(decoded.entries).isNotEmpty()
    }

    @Test
    fun `device logs and crash reports become manifest entries`() {
        val maestro = mockMaestro()

        coEvery { maestro.stopAndCollectDeviceLogs(any()) } answers {
            val dir = firstArg<java.io.File>()
            val logFile = java.io.File(dir, DeviceArtifactFiles.LOGCAT).also { it.writeText("logcat content") }
            listOf(CapturedDeviceArtifact(CapturedDeviceArtifact.Type.DEVICE_LOG, logFile, source = "emulator"))
        }

        coEvery { maestro.collectCrashArtifacts(any(), any(), any()) } answers {
            val dir = thirdArg<java.io.File>()
            val crashFile = java.io.File(dir, DeviceArtifactFiles.CRASH_REPORT).also { it.writeText("crash content") }
            listOf(CapturedDeviceArtifact(CapturedDeviceArtifact.Type.CRASH_REPORT, crashFile, friendlyMessage = "App crashed"))
        }

        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = maestro)
        val cmd = MaestroCommand(launchAppCommand = LaunchAppCommand(appId = "com.x"))

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        val byKind = gen.artifactManifest.entries.associateBy { it.kind }

        val logEntry = byKind[ArtifactKind.DEVICE_LOG]
        assertThat(logEntry).isNotNull()
        assertThat(logEntry!!.relativePath).isEqualTo(DeviceArtifactFiles.LOGCAT)
        assertThat(logEntry.metadata["source"]).isEqualTo("emulator")
        assertThat(logEntry.format).isEqualTo(ArtifactFormat.TXT)

        val crashEntry = byKind[ArtifactKind.CRASH_REPORT]
        assertThat(crashEntry).isNotNull()
        assertThat(crashEntry!!.metadata["message"]).isEqualTo("App crashed")
        assertThat(crashEntry.format).isEqualTo(ArtifactFormat.TXT)
    }

    @Test
    fun `capture failure is swallowed and does not fail the flow or block the manifest`() {
        val maestro = mockMaestro()

        coEvery { maestro.stopAndCollectDeviceLogs(any()) } throws RuntimeException("logcat fail")
        coEvery { maestro.collectCrashArtifacts(any(), any(), any()) } returns emptyList()

        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = maestro)
        val cmd = MaestroCommand(launchAppCommand = LaunchAppCommand(appId = "com.x"))

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        // onFlowEnd completed without throwing
        val entries = gen.artifactManifest.entries
        val kinds = entries.map { it.kind }
        assertThat(kinds).contains(ArtifactKind.COMMAND_METADATA)
        assertThat(kinds).doesNotContain(ArtifactKind.DEVICE_LOG)
    }

    @Test
    fun `registers screenshots and recordings folders as collections`() {
        Files.createDirectories(tempDir.resolve("screenshots/login"))
        Files.write(tempDir.resolve("screenshots/login/home.png"), byteArrayOf(1))
        Files.write(tempDir.resolve("screenshots/splash.png"), byteArrayOf(1))
        Files.createDirectories(tempDir.resolve("recordings"))
        Files.write(tempDir.resolve("recordings/clip.mp4"), byteArrayOf(1))

        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        gen.onFlowStart()
        gen.onFlowEnd()

        val screenshots = gen.artifactManifest.entries
            .single { it.kind == ArtifactKind.SCREENSHOT && it.relativePath == "screenshots" }
        assertThat(screenshots.format).isEqualTo(ArtifactFormat.PNG)
        assertThat(screenshots.count).isEqualTo(2)
        assertThat(screenshots.sizeBytes).isNull()

        val recordings = gen.artifactManifest.entries
            .single { it.kind == ArtifactKind.SCREEN_RECORDING }
        assertThat(recordings.relativePath).isEqualTo("recordings")
        assertThat(recordings.format).isEqualTo(ArtifactFormat.MP4)
        assertThat(recordings.count).isEqualTo(1)
        assertThat(recordings.sizeBytes).isNull()
    }

    @Test
    fun `omits screenshots and recordings entries when folders are absent or empty`() {
        Files.createDirectories(tempDir.resolve("recordings")) // present but empty

        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        gen.onFlowStart()
        gen.onFlowEnd()

        assertThat(gen.artifactManifest.entries.none { it.kind == ArtifactKind.SCREEN_RECORDING }).isTrue()
        assertThat(gen.artifactManifest.entries.none { it.relativePath == "screenshots" }).isTrue()
    }

    @Test
    fun `bundles the schema next to manifest_json and points manifest at it`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, startedAt = 100L, finishedAt = 150L)
        gen.onFlowEnd()

        // The schema travels with the manifest so an agent can resolve it offline.
        val schemaFile = tempDir.resolve("manifest.schema.json").toFile()
        assertThat(schemaFile.exists()).isTrue()
        val schema = jacksonObjectMapper().readTree(schemaFile)
        assertThat(schema["title"].asText()).isEqualTo("ArtifactManifest")

        // manifest.json references the bundled schema by its relative filename.
        val manifest = jacksonObjectMapper().readTree(tempDir.resolve("manifest.json").toFile())
        assertThat(manifest["\$schema"].asText()).isEqualTo("manifest.schema.json")
    }
}
