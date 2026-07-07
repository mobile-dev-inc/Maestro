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
        assertThat(gen.debugOutput.commands[cmd]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.SCREENSHOT, "screenshots/step-0.png"))
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
    fun `onCommandReset leaves an already-recorded execution intact`() {
        val gen = ArtifactsGenerator(artifactsDir = null, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 200L)
        gen.onCommandReset(cmd) // repeat/retry resets before the next iteration

        // The finished execution must stay COMPLETED — reset precedes a new entry.
        assertThat(gen.debugOutput.executedSteps.single().status).isEqualTo(CommandStatus.COMPLETED)
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
        // Command output is allocated through the generator (as Orchestra does via
        // allocateCommandArtifact); the collector folds same-kind files into one entry.
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)
        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.allocateCommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "login/home.png")!!.writeBytes(byteArrayOf(1))
        gen.allocateCommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "splash.png")!!.writeBytes(byteArrayOf(1))
        gen.allocateCommandArtifact(ArtifactKind.START_SCREEN_RECORDING, "clip.mp4")!!.writeBytes(byteArrayOf(1))
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
    fun `per-step screenshot is attributed to its command when captureFullArtifacts is true`() {
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
                CommandArtifact(ArtifactKind.SCREENSHOT, "screenshots/step-3.png"),
            )
    }

    @Test
    fun `captures per-step screenshots into screenshots folder when captureFullArtifacts is true`() {
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
    fun `starts and stops a full-run recording when captureFullArtifacts is true`() {
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
    fun `registers the full-run recording at the run root when captureFullArtifacts is true`() {
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
    fun `command output is attributed to the running command`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.allocateCommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "checkout.png")!!.writeBytes(byteArrayOf(1))
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        assertThat(gen.debugOutput.commands[cmd]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "takeScreenshot/checkout.png"))
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
    fun `command output is attributed only to the command running when allocated`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val first = MaestroCommand(tapOnElement = null)
        val second = MaestroCommand(scrollCommand = ScrollCommand())

        gen.onFlowStart()
        gen.onCommandStart(first, sequenceNumber = 0)
        gen.onCommandFinished(first, CommandOutcome.Completed, 100L, 150L)
        gen.onCommandStart(second, sequenceNumber = 1)
        gen.allocateCommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "checkout.png")!!.writeBytes(byteArrayOf(1))
        gen.onCommandFinished(second, CommandOutcome.Completed, 150L, 200L)
        gen.onFlowEnd()

        // first didn't allocate anything — the screenshot must not leak onto it
        assertThat(gen.debugOutput.commands[first]!!.artifacts).isEmpty()
        // second has the attributed TAKE_SCREENSHOT it allocated
        assertThat(gen.debugOutput.commands[second]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "takeScreenshot/checkout.png"))
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
    fun `allocateCommandArtifact returns null and records nothing when artifactsDir is null`() {
        val gen = ArtifactsGenerator(artifactsDir = null, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        assertThat(gen.allocateCommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "checkout.png")).isNull()
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
            .isEqualTo("https://storage.googleapis.com/maestro-schemas/artifact-manifest/v1.schema.json")

        // No per-run schema file is bundled any more.
        assertThat(tempDir.resolve("manifest.v1.schema.json").toFile().exists()).isFalse()
    }

    @Test
    fun `passing command captures a screenshot but no hierarchy even when captureFullArtifacts is true`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro(), captureFullArtifacts = true)
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 2)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        // Passing steps never pay the per-command viewHierarchy() round-trip.
        assertThat(tempDir.resolve("screen-hierarchy").exists()).isFalse()
        assertThat(gen.artifactManifest.entries.none { it.kind == ArtifactKind.SCREEN_HIERARCHY }).isTrue()
        // Screenshots stay per-step under captureFullArtifacts.
        assertThat(tempDir.resolve("screenshots/step-2.png").exists()).isTrue()
        assertThat(gen.debugOutput.commands[cmd]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.SCREENSHOT, "screenshots/step-2.png"))
    }

    @Test
    fun `passing command gets no hierarchy file when captureFullArtifacts is false`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 2)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("screen-hierarchy").exists()).isFalse()
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
    fun `failed command gets a step screenshot even when captureFullArtifacts is false`() {
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
    fun `warned command gets a step screenshot even when captureFullArtifacts is false`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Warned, 100L, 150L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("screenshots/step-0.png").exists()).isTrue()
        assertThat(gen.debugOutput.commands[cmd]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.SCREENSHOT, "screenshots/step-0.png"))
    }

    @Test
    fun `a re-run command yields one commands entry per execution, each with its own screenshot`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro(), captureFullArtifacts = true)
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        // Two executions of the SAME command object — what repeat/retry does, incl.
        // the reset between iterations (must not downgrade the finished one).
        gen.onCommandStart(cmd, sequenceNumber = 0, depth = 1)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 0L, 1L)
        gen.onCommandReset(cmd)
        gen.onCommandStart(cmd, sequenceNumber = 1, depth = 1)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 1L, 2L)
        gen.onFlowEnd()

        // Each execution is its own entry with its own screenshot — not collapsed.
        assertThat(gen.debugOutput.executedSteps).hasSize(2)
        assertThat(gen.debugOutput.executedSteps.map { it.sequenceNumber }).containsExactly(0, 1).inOrder()
        assertThat(gen.debugOutput.executedSteps.map { it.depth }).containsExactly(1, 1)
        assertThat(gen.debugOutput.executedSteps.map { it.status })
            .containsExactly(CommandStatus.COMPLETED, CommandStatus.COMPLETED)
        assertThat(gen.debugOutput.executedSteps[0].artifacts)
            .contains(CommandArtifact(ArtifactKind.SCREENSHOT, "screenshots/step-0.png"))
        assertThat(gen.debugOutput.executedSteps[1].artifacts)
            .contains(CommandArtifact(ArtifactKind.SCREENSHOT, "screenshots/step-1.png"))
        // commands.json carries both executions, not one collapsed entry.
        val content = Files.readString(tempDir.resolve("commands.json"))
        assertThat(content.split("\"sequenceNumber\"").size - 1).isEqualTo(2)
    }

    @Test
    fun `AI screenshot is recorded as AI_ANALYSIS attributed to the running command`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 3)
        gen.onAIArtifactGenerated(Buffer().write(byteArrayOf(1, 2, 3)), defectCount = 2)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("ai-analysis/step-3.png").exists()).isTrue()
        assertThat(gen.debugOutput.commands[cmd]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.AI_ANALYSIS, "ai-analysis/step-3.png"))
        val entry = gen.artifactManifest.entries.single { it.kind == ArtifactKind.AI_ANALYSIS }
        assertThat(entry.relativePath).isEqualTo("ai-analysis/step-3.png")
        assertThat(entry.metadata["defectCount"]).isEqualTo("2")
    }

    @Test
    fun `the failed command's screenshot is part of the per-step set when captureFullArtifacts is true`() {
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
        val captured = mutableListOf<Pair<Int, String>>()
        val gen = ArtifactsGenerator(
            artifactsDir = tempDir,
            maestro = mockMaestro(),
            onStepScreenshotCaptured = { seq, path -> captured.add(seq to path) },
        )
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
        // The dedup-skipped parent stays silent on the callback channel too.
        assertThat(captured).containsExactly(1 to "screenshots/step-1.png")
    }

    @Test
    fun `a failed composite parent keeps its own screenshot when captureFullArtifacts is true`() {
        // Worker mode records every step individually, so the parent must not be
        // deduped against its leaf — its RunStep needs its own screenshot.
        val captured = mutableListOf<Pair<Int, String>>()
        val gen = ArtifactsGenerator(
            artifactsDir = tempDir,
            maestro = mockMaestro(),
            captureFullArtifacts = true,
            onStepScreenshotCaptured = { seq, path -> captured.add(seq to path) },
        )
        val parent = MaestroCommand(scrollCommand = ScrollCommand())
        val leaf = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(parent, 0)
        gen.onCommandStart(leaf, 1)
        gen.onCommandFinished(leaf, CommandOutcome.Failed(RuntimeException("boom")), 100L, 150L)
        gen.onCommandFinished(parent, CommandOutcome.Failed(RuntimeException("boom")), 100L, 200L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("screenshots/step-1.png").exists()).isTrue()
        assertThat(tempDir.resolve("screenshots/step-0.png").exists()).isTrue()
        assertThat(gen.debugOutput.commands[parent]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.SCREENSHOT, "screenshots/step-0.png"))
        // Parent fires after its leaf with a lower seq — consumers must tolerate out-of-order arrival.
        assertThat(captured)
            .containsExactly(1 to "screenshots/step-1.png", 0 to "screenshots/step-0.png")
            .inOrder()
    }

    @Test
    fun `callback reports the step screenshot path for a completed step when captureFullArtifacts is true`() {
        val captured = mutableListOf<Pair<Int, String>>()
        val gen = ArtifactsGenerator(
            artifactsDir = tempDir,
            maestro = mockMaestro(),
            captureFullArtifacts = true,
            onStepScreenshotCaptured = { seq, path -> captured.add(seq to path) },
        )
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 3)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        assertThat(captured).containsExactly(3 to "screenshots/step-3.png")
    }

    @Test
    fun `callback reports the failure screenshot path for a failed step`() {
        val captured = mutableListOf<Pair<Int, String>>()
        val gen = ArtifactsGenerator(
            artifactsDir = tempDir,
            maestro = mockMaestro(),
            onStepScreenshotCaptured = { seq, path -> captured.add(seq to path) },
        )
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 4)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(RuntimeException("boom")), 100L, 200L)
        gen.onFlowEnd()

        assertThat(captured).containsExactly(4 to "screenshots/step-4.png")
    }

    @Test
    fun `callback reports the step screenshot path for a warned step even when captureFullArtifacts is false`() {
        val captured = mutableListOf<Pair<Int, String>>()
        val gen = ArtifactsGenerator(
            artifactsDir = tempDir,
            maestro = mockMaestro(),
            onStepScreenshotCaptured = { seq, path -> captured.add(seq to path) },
        )
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Warned, 100L, 150L)
        gen.onFlowEnd()

        assertThat(captured).containsExactly(0 to "screenshots/step-0.png")
    }

    @Test
    fun `callback does not fire for a skipped command`() {
        val captured = mutableListOf<Pair<Int, String>>()
        val gen = ArtifactsGenerator(
            artifactsDir = tempDir,
            maestro = mockMaestro(),
            captureFullArtifacts = true,
            onStepScreenshotCaptured = { seq, path -> captured.add(seq to path) },
        )
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Skipped, 100L, 150L)
        gen.onFlowEnd()

        assertThat(captured).isEmpty()
    }

    @Test
    fun `callback does not fire for a passing step when captureFullArtifacts is false`() {
        val captured = mutableListOf<Pair<Int, String>>()
        val gen = ArtifactsGenerator(
            artifactsDir = tempDir,
            maestro = mockMaestro(),
            onStepScreenshotCaptured = { seq, path -> captured.add(seq to path) },
        )
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        assertThat(captured).isEmpty()
    }

    @Test
    fun `callback does not fire when the screenshot write fails`() {
        val maestro: Maestro = mockk(relaxed = true) {
            coEvery { takeScreenshot(any<Sink>(), any()) } throws RuntimeException("screenshot boom")
            coEvery { viewHierarchy(any()) } returns ViewHierarchy(
                TreeNode(attributes = mutableMapOf("text" to "root"))
            )
        }
        val captured = mutableListOf<Pair<Int, String>>()
        val gen = ArtifactsGenerator(
            artifactsDir = tempDir,
            maestro = maestro,
            captureFullArtifacts = true,
            onStepScreenshotCaptured = { seq, path -> captured.add(seq to path) },
        )
        val completed = MaestroCommand(tapOnElement = null)
        val failed = MaestroCommand(scrollCommand = ScrollCommand())

        gen.onFlowStart()
        gen.onCommandStart(completed, sequenceNumber = 0)
        gen.onCommandFinished(completed, CommandOutcome.Completed, 100L, 150L)
        gen.onCommandStart(failed, sequenceNumber = 1)
        gen.onCommandFinished(failed, CommandOutcome.Failed(RuntimeException("boom")), 150L, 200L)
        gen.onFlowEnd()

        assertThat(captured).isEmpty()
        // Mid-write failure leaves no partial file, so bundle and manifest stay empty too.
        assertThat(tempDir.resolve("screenshots/step-0.png").exists()).isFalse()
        assertThat(tempDir.resolve("screenshots/step-1.png").exists()).isFalse()
        assertThat(gen.artifactManifest.entries.filter { it.kind == ArtifactKind.SCREENSHOT }).isEmpty()
        assertThat(gen.debugOutput.commands[completed]!!.artifacts.filter { it.type == ArtifactKind.SCREENSHOT }).isEmpty()
        assertThat(gen.debugOutput.commands[failed]!!.artifacts.filter { it.type == ArtifactKind.SCREENSHOT }).isEmpty()
    }

    @Test
    fun `a throwing consumer propagates instead of being swallowed as a capture failure`() {
        // The callback fires outside the capture try, so a consumer exception propagates to
        // Orchestra's per-listener dispatch net rather than being logged as a capture failure.
        // The screenshot is already on disk, so the bundle stays intact.
        val gen = ArtifactsGenerator(
            artifactsDir = tempDir,
            maestro = mockMaestro(),
            captureFullArtifacts = true,
            onStepScreenshotCaptured = { _, _ -> throw RuntimeException("consumer boom") },
        )
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        val thrown = runCatching {
            gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(RuntimeException::class.java)
        assertThat(thrown!!.message).isEqualTo("consumer boom")
        assertThat(tempDir.resolve("screenshots/step-0.png").exists()).isTrue()
    }

    @Test
    fun `separate failures each keep their own screenshot, not just the first`() {
        // Two sibling commands fail (continue-on-failure / optional): both are real,
        // distinct failures, so the dedup must not swallow the second.
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val first = MaestroCommand(tapOnElement = null)
        val second = MaestroCommand(scrollCommand = ScrollCommand())

        gen.onFlowStart()
        gen.onCommandStart(first, 0)
        gen.onCommandFinished(first, CommandOutcome.Failed(RuntimeException("boom")), 100L, 150L)
        gen.onCommandStart(second, 1)
        gen.onCommandFinished(second, CommandOutcome.Failed(RuntimeException("boom")), 150L, 200L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("screenshots/step-0.png").exists()).isTrue()
        assertThat(tempDir.resolve("screenshots/step-1.png").exists()).isTrue()
        assertThat(gen.debugOutput.commands[first]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.SCREENSHOT, "screenshots/step-0.png"))
        assertThat(gen.debugOutput.commands[second]!!.artifacts)
            .contains(CommandArtifact(ArtifactKind.SCREENSHOT, "screenshots/step-1.png"))
    }
}
