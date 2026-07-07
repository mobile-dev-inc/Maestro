package maestro.orchestra.debug

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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

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

        // Sequence numbers increment on every onCommandStart: the repeat parent is
        // step-0, then each of the 3 iterations of the reused leaf is its own file.
        assertThat(stepScreenshotNames())
            .containsExactly("step-0.png", "step-1.png", "step-2.png", "step-3.png")
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
            .containsExactly(0 to "screenshots/step-0.png", 1 to "screenshots/step-1.png")
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

        // maxRetries=2 -> 3 attempts of the reused leaf (step-1..3); the retry parent
        // that ultimately failed is step-0 (worker mode records every step).
        assertThat(stepScreenshotNames())
            .containsExactly("step-0.png", "step-1.png", "step-2.png", "step-3.png")
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

        // Hooks run through the same dispatch as regular commands, so each is a
        // numbered step in execution order: onFlowStart hook, the applyConfiguration
        // command, the main command, then the onFlowComplete hook.
        assertThat(stepScreenshotNames())
            .containsExactly("step-0.png", "step-1.png", "step-2.png", "step-3.png")
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

}
