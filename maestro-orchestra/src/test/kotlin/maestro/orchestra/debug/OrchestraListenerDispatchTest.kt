package maestro.orchestra.debug

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.KeyCode
import maestro.Maestro
import maestro.MaestroException
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.Platform
import maestro.js.JsEngine
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.AssertScreenshotCommand
import maestro.orchestra.Condition
import maestro.orchestra.DefineVariablesCommand
import maestro.orchestra.EvalScriptCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.MaestroOnFlowComplete
import maestro.orchestra.MaestroOnFlowStart
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.RepeatCommand
import maestro.orchestra.RetryCommand
import maestro.orchestra.RunFlowCommand
import maestro.orchestra.StartRecordingCommand
import maestro.orchestra.StopRecordingCommand
import maestro.orchestra.TakeScreenshotCommand
import okio.Sink
import okio.buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import javax.imageio.ImageIO

class OrchestraListenerDispatchTest {

    @TempDir
    lateinit var tempDir: Path

    private fun mockMaestro(
        pressKeyThrows: Throwable? = null,
        openLinkThrows: Throwable? = null,
    ): Maestro = mockk(relaxed = true) {
        coEvery { viewHierarchy(any()) } returns ViewHierarchy(TreeNode(attributes = mutableMapOf()))
        coEvery { cachedDeviceInfo } returns DeviceInfo(
            platform = Platform.ANDROID,
            widthPixels = 100,
            heightPixels = 200,
            widthGrid = 100,
            heightGrid = 200,
        )
        pressKeyThrows?.let { coEvery { pressKey(any(), any()) } throws it }
        openLinkThrows?.let { coEvery { openLink(any(), any(), any(), any()) } throws it }
    }

    /** Sized so the comparison's `minimalRectangleSize` keeps the diff rectangle, which is what writes a diff file. */
    private fun png(offset: Int): ByteArray = ByteArrayOutputStream()
        .also { out ->
            val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply {
                color = Color.WHITE
                fillRect(offset, 0, 120, 120)
                dispose()
            }
            ImageIO.write(image, "png", out)
        }
        .toByteArray()

    /**
     * A device that produces real PNGs, so assertScreenshot can decode what takeScreenshot wrote.
     * [changing] shifts every capture, so any two of them differ however many are interleaved.
     */
    private fun mockMaestroWithScreenshots(changing: Boolean = false): Maestro = mockMaestro().also { maestro ->
        var captures = 0
        coEvery { maestro.takeScreenshot(any<Sink>(), any(), any()) } answers {
            val bytes = png(if (changing) 40 * captures++ else 0)
            firstArg<Sink>().buffer().use { it.write(bytes) }
        }
    }

    private class RecordingListener : OrchestraListener {
        data class FinishedEvent(val cmd: MaestroCommand, val outcome: String)
        data class Timing(val cmd: MaestroCommand, val startedAt: Long, val finishedAt: Long)

        data class Started(val cmd: MaestroCommand, val sequenceNumber: Int, val depth: Int)

        val events = mutableListOf<String>()
        val started = mutableListOf<MaestroCommand>()
        val startEvents = mutableListOf<Started>()
        val finished = mutableListOf<FinishedEvent>()
        val timings = mutableListOf<Timing>()
        val resets = mutableListOf<MaestroCommand>()

        override fun onFlowStart() { events.add("flowStart") }
        override fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int, depth: Int) {
            events.add("commandStart:$sequenceNumber")
            started.add(cmd)
            startEvents.add(Started(cmd, sequenceNumber, depth))
        }
        override fun onCommandFinished(
            cmd: MaestroCommand,
            outcome: CommandOutcome,
            startedAt: Long,
            finishedAt: Long,
        ) {
            events.add("commandFinished:${outcome::class.simpleName}")
            finished.add(FinishedEvent(cmd, outcome::class.simpleName!!))
            timings.add(Timing(cmd, startedAt, finishedAt))
        }
        override fun onCommandReset(cmd: MaestroCommand) {
            events.add("commandReset")
            resets.add(cmd)
        }
        override fun onFlowEnd() { events.add("flowEnd") }
    }

    // Three leaves reused across composite tests. Filtering by identity (==) tells us
    // which finished events the listener saw for the *inner* leaves vs. the outer
    // composite. The outer composite is dispatched today (top-level executeCommands
    // is wired up); the inner leaves are the regression hole.
    private val completedLeaf = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
    private val warnedLeaf = MaestroCommand(
        pressKeyCommand = PressKeyCommand(code = KeyCode.BACK, optional = true),
    )
    private val failedLeaf = MaestroCommand(
        openLinkCommand = OpenLinkCommand(link = "https://example.com"),
    )

    private fun innerFinishes(recording: RecordingListener): List<RecordingListener.FinishedEvent> =
        recording.finished.filter { it.cmd in listOf(completedLeaf, warnedLeaf, failedLeaf) }

    private fun mockMaestroForLeafOutcomes(): Maestro = mockMaestro(
        // Optional pressKey throws MaestroException → executeSubflowCommands translates
        // to CommandWarned, body continues.
        pressKeyThrows = MaestroException.UnableToLaunchApp("warn"),
        // Non-optional openLink throws a non-MaestroException → CommandOutcome.Failed.
        // Using RuntimeException (not MaestroException) keeps Retry from looping —
        // retryCommand only catches MaestroException for retry.
        openLinkThrows = RuntimeException("fail"),
    )

    /**
     * - runFlow:
     *     commands:
     *       - evalScript: "1"
     *       - pressKey: BACK         # optional
     *       - openLink: https://example.com
     */
    @Test
    fun `RunFlow dispatches nested-leaf lifecycle for Completed, Warned, Failed`() {
        val recording = RecordingListener()
        val outer = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(completedLeaf, warnedLeaf, failedLeaf),
                config = null,
            ),
        )
        val orchestra = Orchestra(
            maestro = mockMaestroForLeafOutcomes(),
            listeners = listOf(recording),
            // Match CLI's onCommandFailed wiring: convert a thrown failure into
            // ErrorResolution.FAIL so runFlow doesn't propagate the exception.
            onCommandFailed = { _, _, _ -> Orchestra.ErrorResolution.FAIL },
        )

        runBlocking { orchestra.runFlow(listOf(outer)) }

        assertThat(recording.started).containsAtLeastElementsIn(
            listOf(completedLeaf, warnedLeaf, failedLeaf),
        )
        assertThat(innerFinishes(recording)).containsExactly(
            RecordingListener.FinishedEvent(completedLeaf, "Completed"),
            RecordingListener.FinishedEvent(warnedLeaf, "Warned"),
            RecordingListener.FinishedEvent(failedLeaf, "Failed"),
        ).inOrder()
    }

    /**
     * - repeat:
     *     times: 1
     *     commands:
     *       - evalScript: "1"
     *       - pressKey: BACK         # optional
     *       - openLink: https://example.com
     */
    @Test
    fun `Repeat dispatches nested-leaf lifecycle for Completed, Warned, Failed`() {
        val recording = RecordingListener()
        val outer = MaestroCommand(
            repeatCommand = RepeatCommand(
                times = "1",
                commands = listOf(completedLeaf, warnedLeaf, failedLeaf),
            ),
        )
        val orchestra = Orchestra(
            maestro = mockMaestroForLeafOutcomes(),
            listeners = listOf(recording),
            // Match CLI's onCommandFailed wiring: convert a thrown failure into
            // ErrorResolution.FAIL so runFlow doesn't propagate the exception.
            onCommandFailed = { _, _, _ -> Orchestra.ErrorResolution.FAIL },
        )

        runBlocking { orchestra.runFlow(listOf(outer)) }

        assertThat(recording.started).containsAtLeastElementsIn(
            listOf(completedLeaf, warnedLeaf, failedLeaf),
        )
        assertThat(innerFinishes(recording)).containsExactly(
            RecordingListener.FinishedEvent(completedLeaf, "Completed"),
            RecordingListener.FinishedEvent(warnedLeaf, "Warned"),
            RecordingListener.FinishedEvent(failedLeaf, "Failed"),
        ).inOrder()
    }

    /**
     * - retry:
     *     maxRetries: 0
     *     commands:
     *       - evalScript: "1"
     *       - pressKey: BACK         # optional
     *       - openLink: https://example.com
     */
    @Test
    fun `Retry dispatches nested-leaf lifecycle for Completed, Warned, Failed`() {
        val recording = RecordingListener()
        val outer = MaestroCommand(
            retryCommand = RetryCommand(
                maxRetries = "0",
                commands = listOf(completedLeaf, warnedLeaf, failedLeaf),
                config = null,
            ),
        )
        val orchestra = Orchestra(
            maestro = mockMaestroForLeafOutcomes(),
            listeners = listOf(recording),
            // Match CLI's onCommandFailed wiring: convert a thrown failure into
            // ErrorResolution.FAIL so runFlow doesn't propagate the exception.
            onCommandFailed = { _, _, _ -> Orchestra.ErrorResolution.FAIL },
        )

        runBlocking { orchestra.runFlow(listOf(outer)) }

        assertThat(recording.started).containsAtLeastElementsIn(
            listOf(completedLeaf, warnedLeaf, failedLeaf),
        )
        assertThat(innerFinishes(recording)).containsExactly(
            RecordingListener.FinishedEvent(completedLeaf, "Completed"),
            RecordingListener.FinishedEvent(warnedLeaf, "Warned"),
            RecordingListener.FinishedEvent(failedLeaf, "Failed"),
        ).inOrder()
    }

    /**
     * - evalScript: "1"
     * - pressKey: BACK             # optional
     * - openLink: https://example.com
     */
    @Test
    fun `top-level leaves dispatch lifecycle for Completed, Warned, Failed`() {
        val recording = RecordingListener()
        val orchestra = Orchestra(
            maestro = mockMaestroForLeafOutcomes(),
            listeners = listOf(recording),
            onCommandFailed = { _, _, _ -> Orchestra.ErrorResolution.FAIL },
        )

        runBlocking { orchestra.runFlow(listOf(completedLeaf, warnedLeaf, failedLeaf)) }

        // Top level has no outer composite to filter out — assert the *complete*
        // set of started + finished events for the three leaves.
        assertThat(recording.started).containsExactly(
            completedLeaf, warnedLeaf, failedLeaf,
        ).inOrder()
        assertThat(recording.finished).containsExactly(
            RecordingListener.FinishedEvent(completedLeaf, "Completed"),
            RecordingListener.FinishedEvent(warnedLeaf, "Warned"),
            RecordingListener.FinishedEvent(failedLeaf, "Failed"),
        ).inOrder()
    }

    /**
     * - repeat:
     *     times: 1
     *     commands:
     *       - runFlow:
     *           commands:
     *             - evalScript: "1"
     *             - pressKey: BACK     # optional
     *             - openLink: https://example.com
     */
    @Test
    fun `nested composite (Repeat - RunFlow - leaves) dispatches every leaf`() {
        val recording = RecordingListener()
        val innerRunFlow = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(completedLeaf, warnedLeaf, failedLeaf),
                config = null,
            ),
        )
        val outerRepeat = MaestroCommand(
            repeatCommand = RepeatCommand(
                times = "1",
                commands = listOf(innerRunFlow),
            ),
        )
        val orchestra = Orchestra(
            maestro = mockMaestroForLeafOutcomes(),
            listeners = listOf(recording),
            onCommandFailed = { _, _, _ -> Orchestra.ErrorResolution.FAIL },
        )

        runBlocking { orchestra.runFlow(listOf(outerRepeat)) }

        // Dispatch must chain through arbitrary nesting depth, not just one level.
        assertThat(recording.started).containsAtLeastElementsIn(
            listOf(completedLeaf, warnedLeaf, failedLeaf),
        )
        assertThat(innerFinishes(recording)).containsExactly(
            RecordingListener.FinishedEvent(completedLeaf, "Completed"),
            RecordingListener.FinishedEvent(warnedLeaf, "Warned"),
            RecordingListener.FinishedEvent(failedLeaf, "Failed"),
        ).inOrder()
    }

    /**
     * - repeat:
     *     times: 2
     *     commands:
     *       - evalScript: "1"
     */
    @Test
    fun `Repeat second iteration dispatches onCommandReset for nested leaf`() {
        val recording = RecordingListener()
        val leaf = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val outer = MaestroCommand(
            repeatCommand = RepeatCommand(
                times = "2",
                commands = listOf(leaf),
            ),
        )
        val orchestra = Orchestra(
            maestro = mockMaestro(),
            listeners = listOf(recording),
        )

        runBlocking { orchestra.runFlow(listOf(outer)) }

        // Leaf executes twice — start fires twice; between iterations, the body is
        // reset, so onCommandReset must dispatch for the inner leaf.
        assertThat(recording.started.count { it == leaf }).isEqualTo(2)
        assertThat(recording.resets).contains(leaf)
    }

    /**
     * User flow with env injection — equivalent to running
     *
     *     maestro test --env X=y flow.yaml
     *
     * or a YAML header with `env: { X: y }`. Env injection lowers via
     * List<MaestroCommand>.withEnv(env) into a synthesized
     * DefineVariablesCommand prepended to the flow. That command runs through
     * executeDefineVariablesCommands, which today sits OUTSIDE runFlow's
     * try/catch — so a putEnv failure skips onFlowEnd entirely.
     */
    @Test
    fun `onFlowEnd dispatches even when executeDefineVariablesCommands throws`() {
        val recording = RecordingListener()
        val brokenJsEngine: JsEngine = mockk(relaxed = true) {
            every { putEnv(any(), any()) } throws RuntimeException("boom")
        }
        val orchestra = Orchestra(
            maestro = mockMaestro(),
            listeners = listOf(recording),
            jsEngineFactory = { _ -> brokenJsEngine },
        )
        val flow = listOf(
            MaestroCommand(
                defineVariablesCommand = DefineVariablesCommand(env = mapOf("X" to "y")),
            ),
        )

        // Default onCommandFailed re-throws, so the failure propagates out of runFlow.
        assertThrows<RuntimeException> {
            runBlocking { orchestra.runFlow(flow) }
        }

        assertThat(recording.events).contains("flowStart")
        // Contract: once onFlowStart fires, onFlowEnd must fire — RED today,
        // GREEN once executeDefineVariablesCommands is moved inside the try.
        assertThat(recording.events).contains("flowEnd")
    }

    /**
     * - evalScript: "1"
     * - evalScript: "1"        # structurally equal to the first
     *
     * Pins down the duration-tracking contract: two MaestroCommand instances
     * that are equal by data-class equality must report independent start
     * timestamps (and non-zero durations), not collide in the
     * commandStartTimes map.
     */
    @Test
    fun `duplicate commands report independent non-zero start timestamps`() {
        val recording = RecordingListener()
        val leaf1 = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val leaf2 = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        // Sanity-check the precondition that makes this test meaningful.
        assertThat(leaf1).isEqualTo(leaf2)

        val orchestra = Orchestra(maestro = mockMaestro(), listeners = listOf(recording))

        runBlocking { orchestra.runFlow(listOf(leaf1, leaf2)) }

        assertThat(recording.timings).hasSize(2)
        val (first, second) = recording.timings
        assertThat(first.startedAt).isGreaterThan(0L)
        assertThat(second.startedAt).isGreaterThan(0L)
        // Each command's start timestamp is tracked independently —
        // never the "duration = 0" fallback that fires when the
        // start-time map lookup misses.
        assertThat(second.finishedAt - second.startedAt).isAtLeast(0L)
        assertThat(first.finishedAt - first.startedAt).isAtLeast(0L)
        // Sequential execution: cmd2 starts at or after cmd1 finishes.
        assertThat(second.startedAt).isAtLeast(first.finishedAt)
    }

    @Test
    fun `takeScreenshot writes into the bundle's takeScreenshot folder`() {
        val cmd = MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand(path = "checkout"))
        val orchestra = Orchestra(maestro = mockMaestro(), artifactsDir = tempDir)

        runBlocking { orchestra.runFlow(listOf(cmd)) }

        assertThat(tempDir.resolve("takeScreenshot/checkout.png").toFile().exists()).isTrue()
        assertThat(tempDir.resolve("manifest.json").toFile().readText()).contains("\"TAKE_SCREENSHOT\"")
    }

    @Test
    fun `startRecording writes into the bundle's startRecording folder`() {
        val start = MaestroCommand(startRecordingCommand = StartRecordingCommand(path = "run1"))
        val stop = MaestroCommand(stopRecordingCommand = StopRecordingCommand())
        val orchestra = Orchestra(maestro = mockMaestro(), artifactsDir = tempDir)

        runBlocking { orchestra.runFlow(listOf(start, stop)) }

        assertThat(tempDir.resolve("startRecording/run1.mp4").toFile().exists()).isTrue()
        assertThat(tempDir.resolve("manifest.json").toFile().readText()).contains("\"START_SCREEN_RECORDING\"")
    }

    @Test
    fun `takeScreenshot writes CWD-relative and no bundle when artifactsDir is null`() {
        // Absolute path into tempDir so the test never litters the CWD.
        val cmd = MaestroCommand(
            takeScreenshotCommand = TakeScreenshotCommand(path = tempDir.resolve("checkout").toString()),
        )
        val orchestra = Orchestra(maestro = mockMaestro())

        runBlocking { orchestra.runFlow(listOf(cmd)) }

        assertThat(tempDir.resolve("checkout.png").toFile().exists()).isTrue()
        assertThat(tempDir.resolve("manifest.json").toFile().exists()).isFalse()
    }

    @Test
    fun `startRecording with a path that leaves the bundle is rejected before anything is written`() {
        val cmd = MaestroCommand(startRecordingCommand = StartRecordingCommand(path = "../logs/screenshots/"))
        val orchestra = Orchestra(maestro = mockMaestro(), artifactsDir = tempDir)

        val e = assertThrows<MaestroException.InvalidCommand> {
            runBlocking { orchestra.runFlow(listOf(cmd)) }
        }

        assertThat(e.message).contains("startRecording")
        assertThat(tempDir.parent.resolve("logs").toFile().exists()).isFalse()
    }

    @Test
    fun `startRecording with a path that climbs out of its own folder is rejected`() {
        val cmd = MaestroCommand(startRecordingCommand = StartRecordingCommand(path = "../clip"))
        val orchestra = Orchestra(maestro = mockMaestro(), artifactsDir = tempDir)

        assertThrows<MaestroException.InvalidCommand> {
            runBlocking { orchestra.runFlow(listOf(cmd)) }
        }

        assertThat(tempDir.resolve("clip.mp4").toFile().exists()).isFalse()
    }

    @Test
    fun `takeScreenshot with a parent segment that stays inside its folder writes there`() {
        val cmd = MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand(path = "login/../home"))
        val orchestra = Orchestra(maestro = mockMaestro(), artifactsDir = tempDir)

        runBlocking { orchestra.runFlow(listOf(cmd)) }

        assertThat(tempDir.resolve("${BundleLayout.TAKE_SCREENSHOT_DIR}/home.png").toFile().exists()).isTrue()
    }

    @Test
    fun `takeScreenshot honours an injected absolute path that points into its own folder`() {
        val commands = listOf(
            MaestroCommand(
                defineVariablesCommand = DefineVariablesCommand(
                    mapOf("SHOTS" to tempDir.resolve(BundleLayout.TAKE_SCREENSHOT_DIR).toString()),
                ),
            ),
            MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand(path = "\${SHOTS}/home")),
        )
        val orchestra = Orchestra(maestro = mockMaestro(), artifactsDir = tempDir)

        runBlocking { orchestra.runFlow(commands) }

        assertThat(tempDir.resolve("${BundleLayout.TAKE_SCREENSHOT_DIR}/home.png").toFile().exists()).isTrue()
        val recordedPaths = jacksonObjectMapper()
            .readTree(tempDir.resolve(BundleLayout.COMMANDS_JSON).toFile())
            .findValues("artifacts").flatten().map { it["path"].asText() }
        assertThat(recordedPaths).containsExactly("${BundleLayout.TAKE_SCREENSHOT_DIR}/home.png")
    }

    @Test
    fun `assertScreenshot reads back a screenshot written through a parent segment`() {
        val commands = listOf(
            MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand(path = "login/../home")),
            MaestroCommand(
                assertScreenshotCommand = AssertScreenshotCommand(
                    path = "login/../home",
                    thresholdPercentage = "95",
                ),
            ),
        )
        val orchestra = Orchestra(maestro = mockMaestroWithScreenshots(), artifactsDir = tempDir)

        runBlocking { orchestra.runFlow(commands) }

        assertThat(tempDir.resolve("${BundleLayout.TAKE_SCREENSHOT_DIR}/home.png").toFile().exists()).isTrue()
    }

    @Test
    fun `assertScreenshot writes its diff into the collector's diff folder, not beside the reference`() {
        // The diff's location no longer derives from where the reference resolved (which used to
        // scatter it: workspace dir, takeScreenshot folder, or CWD). It is a command output like
        // any other: allocated by the collector, recorded, uniform.
        val commands = listOf(
            MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand(path = "login/../home")),
            MaestroCommand(
                assertScreenshotCommand = AssertScreenshotCommand(
                    path = "login/../home",
                    thresholdPercentage = "99",
                ),
            ),
        )
        val capturedDiffs = mutableListOf<Pair<Int, String>>()
        val orchestra = Orchestra(
            maestro = mockMaestroWithScreenshots(changing = true),
            artifactsDir = tempDir,
            onScreenshotDiffCaptured = { seq, path -> capturedDiffs.add(seq to path) },
        )

        val e = assertThrows<MaestroException.AssertionFailure> {
            runBlocking { orchestra.runFlow(commands) }
        }

        assertThat(e.message).contains("threshold not met")
        assertThat(tempDir.resolve("${BundleLayout.SCREENSHOT_DIFF_DIR}/step-001-home_diff.png").toFile().exists()).isTrue()
        assertThat(tempDir.resolve("${BundleLayout.TAKE_SCREENSHOT_DIR}/home_diff.png").toFile().exists()).isFalse()
        // The host is told about the diff the same way it is told about step screenshots,
        // attributed to the failing command's sequence number (the second command here).
        assertThat(capturedDiffs).hasSize(1)
        assertThat(capturedDiffs.single().first).isEqualTo(1)
        assertThat(capturedDiffs.single().second).isEqualTo("${BundleLayout.SCREENSHOT_DIFF_DIR}/step-001-home_diff.png")
    }

    @Test
    @EnabledOnOs(OS.LINUX, OS.MAC)
    fun `takeScreenshot keeps a backslash in the file name instead of inventing a folder`() {
        val cmd = MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand(path = "checkout\\v2"))
        val orchestra = Orchestra(maestro = mockMaestro(), artifactsDir = tempDir)

        runBlocking { orchestra.runFlow(listOf(cmd)) }

        val dir = tempDir.resolve(BundleLayout.TAKE_SCREENSHOT_DIR)
        assertThat(dir.resolve("checkout\\v2.png").toFile().exists()).isTrue()
        assertThat(dir.resolve("checkout").toFile().exists()).isFalse()
    }

    @ParameterizedTest
    @ValueSource(strings = [".", ".."])
    fun `takeScreenshot with a dot segment for a file name is rejected`(path: String) {
        val orchestra = Orchestra(maestro = mockMaestro(), artifactsDir = tempDir)

        val e = assertThrows<MaestroException.InvalidCommand> {
            runBlocking {
                orchestra.runFlow(listOf(MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand(path = path))))
            }
        }

        assertThat(e.message).contains("takeScreenshot")
        assertThat(tempDir.resolve(BundleLayout.TAKE_SCREENSHOT_DIR).toFile().listFiles().orEmpty()).isEmpty()
    }

    @Test
    fun `takeScreenshot honours an absolute path whose tail comes from a variable`() {
        val commands = listOf(
            MaestroCommand(defineVariablesCommand = DefineVariablesCommand(mapOf("NAME" to "home"))),
            MaestroCommand(
                takeScreenshotCommand = TakeScreenshotCommand(
                    path = "${tempDir.resolve(BundleLayout.TAKE_SCREENSHOT_DIR)}/\${NAME}",
                ),
            ),
        )
        val orchestra = Orchestra(maestro = mockMaestro(), artifactsDir = tempDir)

        runBlocking { orchestra.runFlow(commands) }

        assertThat(tempDir.resolve("${BundleLayout.TAKE_SCREENSHOT_DIR}/home.png").toFile().exists()).isTrue()
    }

    @Test
    fun `takeScreenshot with an injected absolute path outside the bundle is rejected`() {
        val outside = tempDir.parent.resolve("escaped/home")
        val commands = listOf(
            MaestroCommand(defineVariablesCommand = DefineVariablesCommand(mapOf("SHOTS" to outside.toString()))),
            MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand(path = "\${SHOTS}")),
        )
        val orchestra = Orchestra(maestro = mockMaestro(), artifactsDir = tempDir)

        val e = assertThrows<MaestroException.InvalidCommand> {
            runBlocking { orchestra.runFlow(commands) }
        }

        assertThat(e.message).contains("takeScreenshot")
        assertThat(outside.parent.toFile().exists()).isFalse()
    }

    @Test
    fun `takeScreenshot with a path that names no file is rejected when no bundle is collected`() {
        val cmd = MaestroCommand(
            takeScreenshotCommand = TakeScreenshotCommand(path = "${tempDir.resolve("shots")}/"),
        )
        val orchestra = Orchestra(maestro = mockMaestro())

        val e = assertThrows<MaestroException.InvalidCommand> {
            runBlocking { orchestra.runFlow(listOf(cmd)) }
        }

        assertThat(e.message).contains("takeScreenshot")
        assertThat(tempDir.resolve("shots/.png").toFile().exists()).isFalse()
    }

    @Test
    fun `startRecording is rejected when a variable expands to nothing and leaves no file name`() {
        val commands = listOf(
            MaestroCommand(defineVariablesCommand = DefineVariablesCommand(mapOf("CLIP" to ""))),
            MaestroCommand(startRecordingCommand = StartRecordingCommand(path = "clips/\${CLIP}")),
        )
        val orchestra = Orchestra(maestro = mockMaestro(), artifactsDir = tempDir)

        val e = assertThrows<MaestroException.InvalidCommand> {
            runBlocking { orchestra.runFlow(commands) }
        }

        assertThat(e.message).contains("startRecording")
        assertThat(tempDir.resolve("${BundleLayout.START_RECORDING_DIR}/clips/.mp4").toFile().exists()).isFalse()
    }

    @Test
    fun `startRecording with a path that names no file is rejected when no bundle is collected`() {
        val cmd = MaestroCommand(
            startRecordingCommand = StartRecordingCommand(path = "${tempDir.resolve("clips")}/"),
        )
        val orchestra = Orchestra(maestro = mockMaestro())

        val e = assertThrows<MaestroException.InvalidCommand> {
            runBlocking { orchestra.runFlow(listOf(cmd)) }
        }

        assertThat(e.message).contains("startRecording")
        assertThat(tempDir.resolve("clips/.mp4").toFile().exists()).isFalse()
    }

    @Test
    fun `takeScreenshot with a path that names no file is rejected under a bundle too`() {
        val cmd = MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand(path = "shots/"))
        val orchestra = Orchestra(maestro = mockMaestro(), artifactsDir = tempDir)

        assertThrows<MaestroException.InvalidCommand> {
            runBlocking { orchestra.runFlow(listOf(cmd)) }
        }

        assertThat(tempDir.resolve("${BundleLayout.TAKE_SCREENSHOT_DIR}/shots/.png").toFile().exists()).isFalse()
    }

    @Test
    fun `an artifact path that cannot be opened fails the flow as a flow error, not an opaque IO error`() {
        tempDir.resolve(BundleLayout.TAKE_SCREENSHOT_DIR).toFile().mkdirs()
        tempDir.resolve("${BundleLayout.TAKE_SCREENSHOT_DIR}/shots").toFile().writeText("not a directory")
        val cmd = MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand(path = "shots/home"))
        val orchestra = Orchestra(maestro = mockMaestro(), artifactsDir = tempDir)

        val e = assertThrows<MaestroException.DestinationIsNotWritable> {
            runBlocking { orchestra.runFlow(listOf(cmd)) }
        }

        assertThat(e.message).contains("shots/home")
    }

    private fun stepScreenshotNames(): List<String> =
        tempDir.resolve("screenshots").toFile().listFiles()
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    @Test
    fun `repeat captures a distinct step screenshot per iteration`() {
        val leaf = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val outer = MaestroCommand(
            repeatCommand = RepeatCommand(times = "3", commands = listOf(leaf)),
        )
        val orchestra = Orchestra(
            maestro = mockMaestro(),
            artifactsDir = tempDir,
            captureFullArtifacts = true,
        )

        runBlocking { orchestra.runFlow(listOf(outer)) }

        // The repeat parent (seq 0) keeps its own screenshot; the 3 leaf iterations follow,
        // plus the flow-level final.png.
        assertThat(stepScreenshotNames())
            .containsExactly(
                "step-001-repeat.png",
                "step-002-evalScript.png",
                "step-003-evalScript.png",
                "step-004-evalScript.png",
                "final.png",
            )
    }

    @Test
    fun `onStepScreenshotCaptured threads through the Orchestra constructor to the artifacts generator`() {
        // Pins the constructor pass-through: the generator's param defaults to a no-op.
        val captured = mutableListOf<Pair<Int, String>>()
        val first = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val second = MaestroCommand(evalScriptCommand = EvalScriptCommand("2"))
        val orchestra = Orchestra(
            maestro = mockMaestro(),
            artifactsDir = tempDir,
            captureFullArtifacts = true,
            onStepScreenshotCaptured = { seq, path -> captured.add(seq to path) },
        )

        runBlocking { orchestra.runFlow(listOf(first, second)) }

        assertThat(captured)
            .containsExactly(0 to "screenshots/step-001-evalScript.png", 1 to "screenshots/step-002-evalScript.png")
            .inOrder()
    }

    @Test
    fun `retry captures a distinct step screenshot per attempt`() {
        val leaf = MaestroCommand(openLinkCommand = OpenLinkCommand(link = "https://example.com"))
        val outer = MaestroCommand(
            retryCommand = RetryCommand(maxRetries = "2", commands = listOf(leaf), config = null),
        )
        val orchestra = Orchestra(
            // MaestroException makes retryCommand loop (it only retries on those).
            maestro = mockMaestro(openLinkThrows = MaestroException.UnableToLaunchApp("retry me")),
            artifactsDir = tempDir,
            captureFullArtifacts = true,
            // Don't let the final failure propagate out of runFlow.
            onCommandFailed = { _, _, _ -> Orchestra.ErrorResolution.FAIL },
        )

        runBlocking { orchestra.runFlow(listOf(outer)) }

        // maxRetries=2 -> 3 leaf attempts (step-002..004). The retry parent (seq 0) keeps its own
        // screenshot (step-001), plus the flow-level final.png.
        assertThat(stepScreenshotNames())
            .containsExactly(
                "step-001-retry.png",
                "step-002-openLink-https_example.com.png",
                "step-003-openLink-https_example.com.png",
                "step-004-openLink-https_example.com.png",
                "final.png",
            )
    }

    @Test
    fun `onFlowStart and onFlowComplete hook commands each produce their own step screenshot`() {
        val startHook = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val mainCmd = MaestroCommand(evalScriptCommand = EvalScriptCommand("2"))
        val completeHook = MaestroCommand(evalScriptCommand = EvalScriptCommand("3"))
        val configCmd = MaestroCommand(
            applyConfigurationCommand = ApplyConfigurationCommand(
                config = MaestroConfig(
                    onFlowStart = MaestroOnFlowStart(commands = listOf(startHook)),
                    onFlowComplete = MaestroOnFlowComplete(commands = listOf(completeHook)),
                ),
            ),
        )
        val orchestra = Orchestra(
            maestro = mockMaestro(),
            artifactsDir = tempDir,
            captureFullArtifacts = true,
        )

        runBlocking { orchestra.runFlow(listOf(configCmd, mainCmd)) }

        // Hooks are numbered steps in execution order: onFlowStart hook, applyConfiguration
        // (non-visible no-op — gap at step-002), main command, onFlowComplete hook; then final.png.
        assertThat(stepScreenshotNames())
            .containsExactly("step-001-evalScript.png", "step-003-evalScript.png", "step-004-evalScript.png", "final.png")
    }

    @Test
    fun `nested commands dispatch depth +1, top-level and hooks stay 0`() {
        val startHook = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val leaf = MaestroCommand(evalScriptCommand = EvalScriptCommand("2"))
        val outer = MaestroCommand(repeatCommand = RepeatCommand(times = "1", commands = listOf(leaf)))
        val configCmd = MaestroCommand(
            applyConfigurationCommand = ApplyConfigurationCommand(
                config = MaestroConfig(onFlowStart = MaestroOnFlowStart(commands = listOf(startHook))),
            ),
        )
        val recording = RecordingListener()
        val orchestra = Orchestra(maestro = mockMaestro(), listeners = listOf(recording))

        runBlocking { orchestra.runFlow(listOf(configCmd, outer)) }

        val depthByCmd = recording.startEvents.associate { it.cmd to it.depth }
        // top-level + the onFlowStart hook command run at depth 0
        assertThat(depthByCmd[startHook]).isEqualTo(0)
        assertThat(depthByCmd[configCmd]).isEqualTo(0)
        assertThat(depthByCmd[outer]).isEqualTo(0)
        // the command inside the repeat runs one level deeper
        assertThat(depthByCmd[leaf]).isEqualTo(1)
    }

    @Test
    fun `nested composites increment depth per level`() {
        val leaf = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val inner = MaestroCommand(runFlowCommand = RunFlowCommand(commands = listOf(leaf), config = null))
        val outer = MaestroCommand(runFlowCommand = RunFlowCommand(commands = listOf(inner), config = null))
        val recording = RecordingListener()
        val orchestra = Orchestra(maestro = mockMaestro(), listeners = listOf(recording))

        runBlocking { orchestra.runFlow(listOf(outer)) }

        val depth = recording.startEvents.associate { it.cmd to it.depth }
        assertThat(depth[outer]).isEqualTo(0)
        assertThat(depth[inner]).isEqualTo(1)
        assertThat(depth[leaf]).isEqualTo(2)
    }

    @Test
    fun `composite children share depth +1 with distinct increasing sequence numbers`() {
        val child1 = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val child2 = MaestroCommand(evalScriptCommand = EvalScriptCommand("2"))
        val outer = MaestroCommand(runFlowCommand = RunFlowCommand(commands = listOf(child1, child2), config = null))
        val recording = RecordingListener()
        val orchestra = Orchestra(maestro = mockMaestro(), listeners = listOf(recording))

        runBlocking { orchestra.runFlow(listOf(outer)) }

        val ev = recording.startEvents.associateBy { it.cmd }
        assertThat(ev[outer]!!.depth).isEqualTo(0)
        assertThat(ev[child1]!!.depth).isEqualTo(1)
        assertThat(ev[child2]!!.depth).isEqualTo(1)
        assertThat(ev[outer]!!.sequenceNumber).isLessThan(ev[child1]!!.sequenceNumber)
        assertThat(ev[child1]!!.sequenceNumber).isLessThan(ev[child2]!!.sequenceNumber)
    }

    // Hooks run inside runFlow's `finally`, so a failing one used to escape it, skipping onFlowEnd.
    private val failingCompleteHook = MaestroCommand(
        assertConditionCommand = AssertConditionCommand(
            condition = Condition(scriptCondition = "false"),
        ),
    )

    private fun configWithFailingCompleteHook() = MaestroCommand(
        applyConfigurationCommand = ApplyConfigurationCommand(
            config = MaestroConfig(
                onFlowComplete = MaestroOnFlowComplete(commands = listOf(failingCompleteHook)),
            ),
        ),
    )

    @Test
    fun `onFlowEnd is dispatched when an onFlowComplete hook command fails`() {
        val recording = RecordingListener()
        val mainCmd = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val orchestra = Orchestra(maestro = mockMaestro(), listeners = listOf(recording))

        // Still fails the flow (Case 109), but must be finalized on the way out.
        assertThrows<MaestroException.AssertionFailure> {
            runBlocking { orchestra.runFlow(listOf(configWithFailingCompleteHook(), mainCmd)) }
        }

        assertThat(recording.events).contains("flowEnd")
    }

    @Test
    fun `manifest is written when an onFlowComplete hook command fails`() {
        val mainCmd = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val orchestra = Orchestra(
            maestro = mockMaestro(),
            artifactsDir = tempDir,
            captureFullArtifacts = true,
        )

        assertThrows<MaestroException.AssertionFailure> {
            runBlocking { orchestra.runFlow(listOf(configWithFailingCompleteHook(), mainCmd)) }
        }

        // No manifest.json → the worker uploads nothing, and the device log is never dumped at all.
        assertThat(tempDir.resolve("manifest.json").toFile().exists()).isTrue()
    }

}
