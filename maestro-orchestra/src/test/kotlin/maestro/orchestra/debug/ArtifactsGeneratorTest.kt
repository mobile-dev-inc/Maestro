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
import maestro.orchestra.ArtifactFormat
import maestro.orchestra.ArtifactKind
import maestro.orchestra.ArtifactManifest
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.debug.CommandArtifact
import maestro.orchestra.ScrollCommand
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
    fun `writes commands_json at the run root at onFlowEnd`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, startedAt = 100L, finishedAt = 150L)
        gen.onFlowEnd()

        val commandsFile = tempDir.resolve("commands.json")
        assertThat(commandsFile.exists()).isTrue()
        val content = Files.readString(commandsFile)
        assertThat(content).contains("\"status\" : \"COMPLETED\"")
    }

    @Test
    fun `writes maestro_log under logs subdir`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, startedAt = 100L, finishedAt = 150L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("logs/maestro.log").exists()).isTrue()
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

        val metadata = gen.debugOutput.commands[cmd]!!
        assertThat(metadata.status).isEqualTo(CommandStatus.FAILED)
        assertThat(metadata.error).isEqualTo(error)
        assertThat(tempDir.resolve("screen-hierarchy/step-0.json").exists()).isTrue()

        // Failure screenshot written under screenshots/.
        assertThat(tempDir.resolve("screenshots/step-0.png").exists()).isTrue()
        assertThat(gen.debugOutput.screenshots).hasSize(1)
        assertThat(gen.debugOutput.screenshots[0].status).isEqualTo(CommandStatus.FAILED)
    }

    @Test
    fun `screenshot failure does not block hierarchy capture`() {
        // Screenshot throws; hierarchy file still lands.
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

        assertThat(tempDir.resolve("screen-hierarchy/step-0.json").exists()).isTrue()
        // No screenshot file landed (capture threw)
        assertThat(tempDir.resolve("screenshots/step-0.png").exists()).isFalse()
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

        assertThat(tempDir.resolve("screen-hierarchy/step-0.json").exists()).isFalse()
        assertThat(tempDir.resolve("screenshots/step-0.png").exists()).isTrue()
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
    fun `manifest exposes command metadata and maestro log entries at the run root`() {
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

        val logEntry = byKind[ArtifactKind.MAESTRO_LOG]!!
        assertThat(logEntry.relativePath).isEqualTo("logs/maestro.log")
    }

    @Test
    fun `failed run yields a single SCREENSHOT folder entry for the screenshots dir`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(RuntimeException("boom")), 100L, 200L)
        gen.onFlowEnd()

        val shots = gen.artifactManifest.entries.filter { it.kind == ArtifactKind.SCREENSHOT }
        assertThat(shots).hasSize(1)
        assertThat(shots[0].format).isEqualTo(ArtifactFormat.PNG)
        assertThat(shots[0].relativePath).isEqualTo("screenshots")
        assertThat(shots[0].count).isEqualTo(1)
        assertThat(shots[0].metadata).isEmpty()
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
        // Device artifacts nest under logs/, alongside maestro.log, so the whole
        // run-root bundle is zippable in one shot.
        assertThat(logEntry!!.relativePath).isEqualTo("${BundleLayout.LOGS_DIR}/${DeviceArtifactFiles.LOGCAT}")
        assertThat(logEntry.metadata["source"]).isEqualTo("emulator")
        assertThat(logEntry.format).isEqualTo(ArtifactFormat.TXT)
        assertThat(tempDir.resolve("${BundleLayout.LOGS_DIR}/${DeviceArtifactFiles.LOGCAT}").exists()).isTrue()

        val crashEntry = byKind[ArtifactKind.CRASH_REPORT]
        assertThat(crashEntry).isNotNull()
        assertThat(crashEntry!!.relativePath).isEqualTo("${BundleLayout.LOGS_DIR}/${DeviceArtifactFiles.CRASH_REPORT}")
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
    fun `registers takeScreenshot and startRecording folders as collections`() {
        // The command-output files are written by Orchestra and reported via
        // onCommandArtifact; the collector folds same-kind files into one entry.
        Files.createDirectories(tempDir.resolve("takeScreenshot/login"))
        Files.write(tempDir.resolve("takeScreenshot/login/home.png"), byteArrayOf(1))
        Files.write(tempDir.resolve("takeScreenshot/splash.png"), byteArrayOf(1))
        Files.createDirectories(tempDir.resolve("startRecording"))
        Files.write(tempDir.resolve("startRecording/clip.mp4"), byteArrayOf(1))

        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)
        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "takeScreenshot/login/home.png")
        gen.onCommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "takeScreenshot/splash.png")
        gen.onCommandArtifact(ArtifactKind.START_SCREEN_RECORDING, "startRecording/clip.mp4")
        gen.onFlowEnd()

        val takeScreenshot = gen.artifactManifest.entries
            .single { it.kind == ArtifactKind.TAKE_SCREENSHOT && it.relativePath == "takeScreenshot" }
        assertThat(takeScreenshot.format).isEqualTo(ArtifactFormat.PNG)
        assertThat(takeScreenshot.count).isEqualTo(2)
        assertThat(takeScreenshot.sizeBytes).isNull()
        assertThat(takeScreenshot.metadata).isEmpty()

        val startRecording = gen.artifactManifest.entries
            .single { it.kind == ArtifactKind.START_SCREEN_RECORDING && it.relativePath == "startRecording" }
        assertThat(startRecording.format).isEqualTo(ArtifactFormat.MP4)
        assertThat(startRecording.count).isEqualTo(1)
        assertThat(startRecording.sizeBytes).isNull()
        assertThat(startRecording.metadata).isEmpty()
    }

    @Test
    fun `omits takeScreenshot and startRecording entries when folders are absent or empty`() {
        Files.createDirectories(tempDir.resolve("startRecording")) // present but empty

        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        gen.onFlowStart()
        gen.onFlowEnd()

        assertThat(gen.artifactManifest.entries.none { it.relativePath == "startRecording" }).isTrue()
        assertThat(gen.artifactManifest.entries.none { it.relativePath == "takeScreenshot" }).isTrue()
    }

    @Test
    fun `per-step screenshot is attributed to its command when the flag is on`() {
        val gen = ArtifactsGenerator(
            artifactsDir = tempDir,
            maestro = mockMaestro(),
            captureFullArtifacts = true,
        )
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 3)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        assertThat(gen.debugOutput.commands[cmd]!!.artifacts)
            .containsExactly(
                CommandArtifact(ArtifactKind.SCREEN_HIERARCHY, "screen-hierarchy/step-3.json"),
                CommandArtifact(ArtifactKind.SCREENSHOT, "screenshots/step-3.png"),
            )
    }

    @Test
    fun `captures per-step screenshots into screenshots folder when the flag is on`() {
        val gen = ArtifactsGenerator(
            artifactsDir = tempDir,
            maestro = mockMaestro(),
            captureFullArtifacts = true,
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
        assertThat(steps.metadata).isEmpty()
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
            captureFullArtifacts = true,
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
    fun `registers the full-run recording at the run root`() {
        // The recording is allocated through the collector when the flag is on;
        // the driver streams bytes into the allocated sink.
        val maestro = mockMaestro()
        coEvery { maestro.startScreenRecording(any()) } answers {
            val sink = firstArg<Sink>()
            val buffer = Buffer().write(byteArrayOf(1, 2, 3))
            sink.write(buffer, buffer.size)
            sink.flush()
            mockk(relaxed = true)
        }

        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = maestro, captureFullArtifacts = true)
        gen.onFlowStart()
        gen.onFlowEnd()

        val recording = gen.artifactManifest.entries
            .single { it.kind == ArtifactKind.SCREEN_RECORDING && it.relativePath == "screen-recording.mp4" }
        assertThat(recording.format).isEqualTo(ArtifactFormat.MP4)
        assertThat(recording.count).isNull()
        assertThat(recording.sizeBytes).isGreaterThan(0L)
        assertThat(recording.metadata).isEmpty()
    }

    @Test
    fun `onCommandArtifact attributes the path to the currently running command`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        // Orchestra writes the file before dispatching onCommandArtifact.
        Files.createDirectories(tempDir.resolve("takeScreenshot"))
        Files.write(tempDir.resolve("takeScreenshot/checkout.png"), byteArrayOf(1))
        gen.onCommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "takeScreenshot/checkout.png")
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        assertThat(gen.debugOutput.commands[cmd]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "takeScreenshot/checkout.png"))
        assertThat(gen.debugOutput.commands[cmd]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.SCREEN_HIERARCHY, "screen-hierarchy/step-0.json"))
        val content = Files.readString(tempDir.resolve("commands.json"))
        assertThat(content).contains("\"type\" : \"TAKE_SCREENSHOT\"")
        assertThat(content).contains("\"path\" : \"takeScreenshot/checkout.png\"")
    }

    @Test
    fun `commands without artifacts omit the artifacts key from commands_json`() {
        // Skipped commands produce no artifacts — use one to pin the NON_EMPTY omission.
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Skipped, 100L, 150L)
        gen.onFlowEnd()

        val content = Files.readString(tempDir.resolve("commands.json"))
        assertThat(content).doesNotContain("\"artifacts\"")
    }

    @Test
    fun `onCommandArtifact attributes only to the command running at dispatch time`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val first = MaestroCommand(tapOnElement = null)
        val second = MaestroCommand(scrollCommand = ScrollCommand())

        gen.onFlowStart()
        gen.onCommandStart(first, sequenceNumber = 0)
        gen.onCommandFinished(first, CommandOutcome.Completed, 100L, 150L)
        gen.onCommandStart(second, sequenceNumber = 1)
        // Orchestra writes the file before dispatching onCommandArtifact.
        Files.createDirectories(tempDir.resolve("takeScreenshot"))
        Files.write(tempDir.resolve("takeScreenshot/checkout.png"), byteArrayOf(1))
        gen.onCommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "takeScreenshot/checkout.png")
        gen.onCommandFinished(second, CommandOutcome.Completed, 150L, 200L)
        gen.onFlowEnd()

        // first has only its hierarchy artifact (no screenshot, no TAKE_SCREENSHOT)
        assertThat(gen.debugOutput.commands[first]!!.artifacts)
            .containsExactly(CommandArtifact(ArtifactKind.SCREEN_HIERARCHY, "screen-hierarchy/step-0.json"))
        // second has hierarchy + the externally attributed TAKE_SCREENSHOT
        assertThat(gen.debugOutput.commands[second]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "takeScreenshot/checkout.png"))
        assertThat(gen.debugOutput.commands[second]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.SCREEN_HIERARCHY, "screen-hierarchy/step-1.json"))
    }

    @Test
    fun `failure screenshot is attributed to the failed command's artifacts`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(RuntimeException("boom")), 100L, 200L)
        gen.onFlowEnd()

        val artifacts = gen.debugOutput.commands[cmd]!!.artifacts
        assertThat(artifacts).hasSize(2)
        assertThat(artifacts.map { it.type })
            .containsExactly(ArtifactKind.SCREEN_HIERARCHY, ArtifactKind.SCREENSHOT)
        val screenshotArtifact = artifacts.single { it.type == ArtifactKind.SCREENSHOT }
        assertThat(screenshotArtifact.path).isEqualTo("screenshots/step-0.png")
        assertThat(tempDir.resolve(screenshotArtifact.path).exists()).isTrue()
    }

    @Test
    fun `onCommandArtifact is a no-op when artifactsDir is null`() {
        val gen = ArtifactsGenerator(artifactsDir = null, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "checkout.png")
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        assertThat(gen.debugOutput.commands[cmd]!!.artifacts).isEmpty()
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
            .isEqualTo("https://raw.githubusercontent.com/mobile-dev-inc/Maestro/main/maestro-orchestra-models/src/main/resources/maestro/orchestra/manifest.v1.schema.json")

        // No per-run schema file is bundled any more.
        assertThat(tempDir.resolve("manifest.v1.schema.json").toFile().exists()).isFalse()
    }

    @Test
    fun `writes a hierarchy file per executed command and attributes it`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 2)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("screen-hierarchy/step-2.json").exists()).isTrue()
        assertThat(gen.debugOutput.commands[cmd]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.SCREEN_HIERARCHY, "screen-hierarchy/step-2.json"))
        val folder = gen.artifactManifest.entries.single { it.kind == ArtifactKind.SCREEN_HIERARCHY }
        assertThat(folder.relativePath).isEqualTo("screen-hierarchy")
        assertThat(folder.format).isEqualTo(ArtifactFormat.JSON)
        assertThat(folder.count).isEqualTo(1)
    }

    @Test
    fun `skipped commands get no hierarchy file`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Skipped, 100L, 150L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("screen-hierarchy").exists()).isFalse()
    }

    @Test
    fun `failed command gets a hierarchy file and commands_json has no inline hierarchy`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(RuntimeException("boom")), 100L, 200L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("screen-hierarchy/step-0.json").exists()).isTrue()
        assertThat(gen.debugOutput.commands[cmd]!!.artifacts.map { it.type })
            .containsExactly(ArtifactKind.SCREEN_HIERARCHY, ArtifactKind.SCREENSHOT)
        val content = Files.readString(tempDir.resolve("commands.json"))
        assertThat(content).doesNotContain("\"hierarchy\"")
    }

    @Test
    fun `failed command gets a step screenshot even with the flag off`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 4)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(RuntimeException("boom")), 100L, 200L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("screenshots/step-4.png").exists()).isTrue()
        assertThat(gen.debugOutput.commands[cmd]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.SCREENSHOT, "screenshots/step-4.png"))
        assertThat(tempDir.toFile().listFiles { _, n -> n.startsWith("screenshot-") }).isEmpty()
    }

    @Test
    fun `with the flag on the failed command's screenshot is part of the per-step set`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro(), captureFullArtifacts = true)
        val ok = MaestroCommand(tapOnElement = null)
        val bad = MaestroCommand(scrollCommand = ScrollCommand())

        gen.onFlowStart()
        gen.onCommandStart(ok, 0)
        gen.onCommandFinished(ok, CommandOutcome.Completed, 100L, 150L)
        gen.onCommandStart(bad, 1)
        gen.onCommandFinished(bad, CommandOutcome.Failed(RuntimeException("boom")), 150L, 200L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("screenshots/step-0.png").exists()).isTrue()
        assertThat(tempDir.resolve("screenshots/step-1.png").exists()).isTrue()
        val steps = gen.artifactManifest.entries.single { it.kind == ArtifactKind.SCREENSHOT }
        assertThat(steps.relativePath).isEqualTo("screenshots")
        assertThat(steps.count).isEqualTo(2)
    }

    @Test
    fun `serialized error carries only message and debugMessage, no stack trace or hierarchy`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)
        val error = MaestroException.AssertionFailure(
            message = "Assertion is false",
            hierarchyRoot = TreeNode(attributes = mutableMapOf("text" to "huge tree")),
            debugMessage = "debug detail",
        )

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(error), 100L, 200L)
        gen.onFlowEnd()

        val content = Files.readString(tempDir.resolve("commands.json"))
        assertThat(content).contains("Assertion is false")
        assertThat(content).contains("debug detail")
        assertThat(content).doesNotContain("stackTrace")
        assertThat(content).doesNotContain("hierarchyRoot")
        assertThat(content).doesNotContain("huge tree")
    }

    @Test
    fun `composite parent failing after its leaf does not duplicate the failure screenshot`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val leaf = MaestroCommand(tapOnElement = null)
        val parent = MaestroCommand(scrollCommand = ScrollCommand())

        gen.onFlowStart()
        gen.onCommandStart(parent, 0)
        gen.onCommandStart(leaf, 1)
        gen.onCommandFinished(leaf, CommandOutcome.Failed(RuntimeException("boom")), 100L, 150L)
        gen.onCommandFinished(parent, CommandOutcome.Failed(RuntimeException("boom")), 100L, 200L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("screenshots/step-1.png").exists()).isTrue()
        assertThat(tempDir.resolve("screenshots/step-0.png").exists()).isFalse()
        // Parent gets hierarchy only — screenshot deduped because leaf already captured it.
        assertThat(gen.debugOutput.commands[parent]!!.artifacts)
            .containsExactly(CommandArtifact(ArtifactKind.SCREEN_HIERARCHY, "screen-hierarchy/step-0.json"))
    }
}
