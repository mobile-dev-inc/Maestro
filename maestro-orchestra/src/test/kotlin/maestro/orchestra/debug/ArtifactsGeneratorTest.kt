package maestro.orchestra.debug

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import maestro.Maestro
import maestro.MaestroException
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.CapturedDeviceArtifact
import maestro.device.DeviceArtifactFiles
import maestro.orchestra.ArtifactFiles
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
    fun `writes commands_json under artifacts subdir at onFlowEnd`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, startedAt = 100L, finishedAt = 150L)
        gen.onFlowEnd()

        val commandsFile = tempDir.resolve("artifacts/commands.json")
        assertThat(commandsFile.exists()).isTrue()
        val content = Files.readString(commandsFile)
        assertThat(content).contains("\"status\" : \"COMPLETED\"")
    }

    @Test
    fun `writes maestro_log under artifacts logs subdir`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, startedAt = 100L, finishedAt = 150L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("artifacts/logs/maestro.log").exists()).isTrue()
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

        // Failure screenshot written as a separate file inside the artifacts subdir.
        val screenshots = tempDir.resolve("artifacts").toFile().listFiles { _, n -> n.startsWith("screenshot-❌-") }
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
        val screenshots = tempDir.resolve("artifacts").toFile().listFiles { _, n -> n.startsWith("screenshot-❌-") } ?: emptyArray()
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
        val screenshots = tempDir.resolve("artifacts").toFile().listFiles { _, n -> n.startsWith("screenshot-❌-") }
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
    fun `manifest exposes command metadata and maestro log entries under artifacts`() {
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
        assertThat(cmdEntry.relativePath).isEqualTo("artifacts/commands.json")
        assertThat(cmdEntry.format).isEqualTo(ArtifactFormat.JSON)
        assertThat(cmdEntry.sizeBytes).isGreaterThan(0L)

        val logEntry = byKind[ArtifactKind.MAESTRO_LOG]!!
        assertThat(logEntry.relativePath).isEqualTo("artifacts/logs/maestro.log")
    }

    @Test
    fun `manifest includes a failure screenshot entry under artifacts with source failure`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(RuntimeException("boom")), 100L, 200L)
        gen.onFlowEnd()

        val shots = gen.artifactManifest.entries.filter { it.kind == ArtifactKind.SCREENSHOT }
        assertThat(shots).hasSize(1)
        assertThat(shots[0].format).isEqualTo(ArtifactFormat.PNG)
        assertThat(shots[0].relativePath).startsWith("artifacts/screenshot-❌-")
        assertThat(shots[0].metadata["source"]).isEqualTo("failure")
    }

    @Test
    fun `writes manifest_json to artifactsDir root at onFlowEnd`() {
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
        // Device artifacts nest under artifacts/logs/, like maestro.log, so the
        // whole artifacts/ bundle is zippable in one shot.
        assertThat(logEntry!!.relativePath).isEqualTo("${ArtifactFiles.LOGS_DIR}/${DeviceArtifactFiles.LOGCAT}")
        assertThat(logEntry.metadata["source"]).isEqualTo("emulator")
        assertThat(logEntry.format).isEqualTo(ArtifactFormat.TXT)
        assertThat(tempDir.resolve("${ArtifactFiles.LOGS_DIR}/${DeviceArtifactFiles.LOGCAT}").exists()).isTrue()

        val crashEntry = byKind[ArtifactKind.CRASH_REPORT]
        assertThat(crashEntry).isNotNull()
        assertThat(crashEntry!!.relativePath).isEqualTo("${ArtifactFiles.LOGS_DIR}/${DeviceArtifactFiles.CRASH_REPORT}")
        assertThat(crashEntry.metadata["message"]).isEqualTo("App crashed")
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
    fun `registers takeScreenshot and startRecording folders as collections with source`() {
        Files.createDirectories(tempDir.resolve("artifacts/takeScreenshot/login"))
        Files.write(tempDir.resolve("artifacts/takeScreenshot/login/home.png"), byteArrayOf(1))
        Files.write(tempDir.resolve("artifacts/takeScreenshot/splash.png"), byteArrayOf(1))
        Files.createDirectories(tempDir.resolve("artifacts/startRecording"))
        Files.write(tempDir.resolve("artifacts/startRecording/clip.mp4"), byteArrayOf(1))

        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        gen.onFlowStart()
        gen.onFlowEnd()

        val takeScreenshot = gen.artifactManifest.entries
            .single { it.kind == ArtifactKind.SCREENSHOT && it.relativePath == "artifacts/takeScreenshot" }
        assertThat(takeScreenshot.format).isEqualTo(ArtifactFormat.PNG)
        assertThat(takeScreenshot.count).isEqualTo(2)
        assertThat(takeScreenshot.sizeBytes).isNull()
        assertThat(takeScreenshot.metadata["source"]).isEqualTo("take_screenshot")

        val startRecording = gen.artifactManifest.entries
            .single { it.kind == ArtifactKind.SCREEN_RECORDING && it.relativePath == "artifacts/startRecording" }
        assertThat(startRecording.format).isEqualTo(ArtifactFormat.MP4)
        assertThat(startRecording.count).isEqualTo(1)
        assertThat(startRecording.sizeBytes).isNull()
        assertThat(startRecording.metadata["source"]).isEqualTo("start_recording")
    }

    @Test
    fun `omits takeScreenshot and startRecording entries when folders are absent or empty`() {
        Files.createDirectories(tempDir.resolve("artifacts/startRecording")) // present but empty

        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        gen.onFlowStart()
        gen.onFlowEnd()

        assertThat(gen.artifactManifest.entries.none { it.relativePath == "artifacts/startRecording" }).isTrue()
        assertThat(gen.artifactManifest.entries.none { it.relativePath == "artifacts/takeScreenshot" }).isTrue()
    }

    @Test
    fun `captures per-step screenshots into screenshots folder when the flag is on`() {
        val gen = ArtifactsGenerator(
            artifactsDir = tempDir,
            maestro = mockMaestro(),
            captureStepScreenshots = true,
        )
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 3)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("screenshots/step-3.png").exists()).isTrue()

        val steps = gen.artifactManifest.entries
            .single { it.kind == ArtifactKind.SCREENSHOT && it.relativePath == "screenshots" }
        assertThat(steps.count).isEqualTo(1)
        assertThat(steps.metadata["source"]).isEqualTo("step")
    }

    @Test
    fun `does not capture per-step screenshots by default`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("screenshots").exists()).isFalse()
        assertThat(gen.artifactManifest.entries.none { it.relativePath == "screenshots" }).isTrue()
    }

    @Test
    fun `starts and stops a full-run recording when the flag is on`() {
        val maestro = mockMaestro()
        val gen = ArtifactsGenerator(
            artifactsDir = tempDir,
            maestro = maestro,
            captureScreenRecording = true,
        )

        gen.onFlowStart()
        gen.onFlowEnd()

        coVerify { maestro.startScreenRecording(any()) }
    }

    @Test
    fun `does not start a full-run recording by default`() {
        val maestro = mockMaestro()
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = maestro)

        gen.onFlowStart()
        gen.onFlowEnd()

        coVerify(exactly = 0) { maestro.startScreenRecording(any()) }
    }

    @Test
    fun `registers the full-run recording at the run root with source full_run`() {
        Files.write(tempDir.resolve("screen-recording.mp4"), byteArrayOf(1, 2, 3))

        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        gen.onFlowStart()
        gen.onFlowEnd()

        val recording = gen.artifactManifest.entries
            .single { it.kind == ArtifactKind.SCREEN_RECORDING && it.relativePath == "screen-recording.mp4" }
        assertThat(recording.format).isEqualTo(ArtifactFormat.MP4)
        assertThat(recording.count).isNull()
        assertThat(recording.sizeBytes).isGreaterThan(0L)
        assertThat(recording.metadata["source"]).isEqualTo("full_run")
    }

    @Test
    fun `points manifest at the stable schema URL and bundles no schema file`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, startedAt = 100L, finishedAt = 150L)
        gen.onFlowEnd()

        // The manifest's identity is a stable, versioned URL — so it stays
        // self-describing even after it's moved away from its run folder.
        val manifest = jacksonObjectMapper().readTree(tempDir.resolve("manifest.json").toFile())
        assertThat(manifest["\$schema"].asText())
            .isEqualTo("https://raw.githubusercontent.com/mobile-dev-inc/Maestro/main/maestro-orchestra-models/src/main/resources/maestro/orchestra/manifest.schema.json")

        // No per-run schema file is bundled any more.
        assertThat(tempDir.resolve("manifest.schema.json").toFile().exists()).isFalse()
    }
}
