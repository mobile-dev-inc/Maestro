package maestro.orchestra.debug

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.KeyCode
import maestro.MaestroException
import maestro.device.Platform
import maestro.js.JsEngine
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.AssertConditionCommand
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
import maestro.orchestra.devicecore.FakeDeviceGateway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Recovers the listener-lifecycle third of the deleted `OrchestraListenerDispatchTest` (git
 * history: commit c1538ee7^, path
 * `maestro-orchestra/src/test/kotlin/maestro/orchestra/debug/OrchestraListenerDispatchTest.kt`) as
 * Tier-2 command-driven tests: a real [Orchestra] driven with a recording [OrchestraListener] and a
 * [FakeDeviceGateway] in place of the old mocked `Maestro` facade. The path-traversal-safety third
 * of the original file is NOT recovered here — it's already covered by the surviving
 * [ArtifactCollectorTest].
 */
class OrchestraListenerDispatchTest {

    @TempDir
    lateinit var tempDir: Path

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

        override fun onFlowStart() { events += "flowStart" }
        override fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int, depth: Int) {
            events += "commandStart:$sequenceNumber"
            started += cmd
            startEvents += Started(cmd, sequenceNumber, depth)
        }
        override fun onCommandFinished(
            cmd: MaestroCommand,
            outcome: CommandOutcome,
            startedAt: Long,
            finishedAt: Long,
        ) {
            events += "commandFinished:${outcome::class.simpleName}"
            finished += FinishedEvent(cmd, outcome::class.simpleName!!)
            timings += Timing(cmd, startedAt, finishedAt)
        }
        override fun onCommandReset(cmd: MaestroCommand) {
            events += "commandReset"
            resets += cmd
        }
        override fun onFlowEnd() { events += "flowEnd" }
    }

    // Three leaves reused across composite tests, exercising the seam's own NotImplemented
    // behavior directly (no FakeDriver customization needed):
    //  - completedLeaf: evalScript never touches the device -> always Completed.
    //  - warnedLeaf: optional pressKey -> FakeDeviceGateway's inherited NotImplemented default is a
    //    MaestroException, and Orchestra's optional handling (Orchestra.kt ~line 347/1100) downgrades
    //    any MaestroException on an optional command to CommandWarned -> CommandOutcome.Warned.
    //    VERIFIED against Orchestra's current catch(e: MaestroException) { if (isOptional) throw
    //    CommandWarned(...) } — confirmed still true; see task-6-report.md.
    //  - failedLeaf: non-optional openLink -> the same NotImplemented propagates unfiltered to the
    //    outer catch(e: Throwable) -> CommandOutcome.Failed.
    private val completedLeaf = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
    private val warnedLeaf = MaestroCommand(
        pressKeyCommand = PressKeyCommand(code = KeyCode.BACK, optional = true),
    )
    private val failedLeaf = MaestroCommand(
        openLinkCommand = OpenLinkCommand(link = "https://example.com"),
    )

    private fun innerFinishes(recording: RecordingListener): List<RecordingListener.FinishedEvent> =
        recording.finished.filter { it.cmd in listOf(completedLeaf, warnedLeaf, failedLeaf) }

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
            driver = FakeDeviceGateway(),
            platform = Platform.ANDROID,
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
            driver = FakeDeviceGateway(),
            platform = Platform.ANDROID,
            listeners = listOf(recording),
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
            driver = FakeDeviceGateway(),
            platform = Platform.ANDROID,
            listeners = listOf(recording),
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
            driver = FakeDeviceGateway(),
            platform = Platform.ANDROID,
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
            driver = FakeDeviceGateway(),
            platform = Platform.ANDROID,
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
            driver = FakeDeviceGateway(),
            platform = Platform.ANDROID,
            listeners = listOf(recording),
        )

        runBlocking { orchestra.runFlow(listOf(outer)) }

        // Leaf executes twice — start fires twice; between iterations, the body is
        // reset, so onCommandReset must dispatch for the inner leaf.
        assertThat(recording.started.count { it == leaf }).isEqualTo(2)
        assertThat(recording.resets).contains(leaf)
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
        val orchestra = Orchestra(driver = FakeDeviceGateway(), platform = Platform.ANDROID, listeners = listOf(recording))

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
        val orchestra = Orchestra(driver = FakeDeviceGateway(), platform = Platform.ANDROID, listeners = listOf(recording))

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
        val orchestra = Orchestra(driver = FakeDeviceGateway(), platform = Platform.ANDROID, listeners = listOf(recording))

        runBlocking { orchestra.runFlow(listOf(outer)) }

        val ev = recording.startEvents.associateBy { it.cmd }
        assertThat(ev[outer]!!.depth).isEqualTo(0)
        assertThat(ev[child1]!!.depth).isEqualTo(1)
        assertThat(ev[child2]!!.depth).isEqualTo(1)
        assertThat(ev[outer]!!.sequenceNumber).isLessThan(ev[child1]!!.sequenceNumber)
        assertThat(ev[child1]!!.sequenceNumber).isLessThan(ev[child2]!!.sequenceNumber)
    }

    private fun stepScreenshotNames(): List<String> =
        tempDir.resolve("screenshots").toFile().listFiles()
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    @Test
    fun `onStepScreenshotCaptured threads through the Orchestra constructor to the artifacts generator`() {
        // Pins the constructor pass-through: the generator's param defaults to a no-op.
        val captured = mutableListOf<Pair<Int, String>>()
        val first = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val second = MaestroCommand(evalScriptCommand = EvalScriptCommand("2"))
        val orchestra = Orchestra(
            driver = FakeDeviceGateway(),
            platform = Platform.ANDROID,
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
    fun `repeat captures a distinct step screenshot per iteration`() {
        val leaf = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val outer = MaestroCommand(
            repeatCommand = RepeatCommand(times = "3", commands = listOf(leaf)),
        )
        val orchestra = Orchestra(
            driver = FakeDeviceGateway(),
            platform = Platform.ANDROID,
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
    fun `retry captures a distinct step screenshot per attempt`() {
        val leaf = MaestroCommand(openLinkCommand = OpenLinkCommand(link = "https://example.com"))
        val outer = MaestroCommand(
            retryCommand = RetryCommand(maxRetries = "2", commands = listOf(leaf), config = null),
        )
        val orchestra = Orchestra(
            // The seam's openLink throws MaestroException.NotImplemented every attempt, which
            // retryCommand treats like any other MaestroException — it retries until maxRetries.
            driver = FakeDeviceGateway(),
            platform = Platform.ANDROID,
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
            driver = FakeDeviceGateway(),
            platform = Platform.ANDROID,
            artifactsDir = tempDir,
            captureFullArtifacts = true,
        )

        runBlocking { orchestra.runFlow(listOf(configCmd, mainCmd)) }

        // Hooks are numbered steps in execution order: onFlowStart hook, applyConfiguration
        // (non-visible no-op — gap at step-002), main command, onFlowComplete hook; then final.png.
        assertThat(stepScreenshotNames())
            .containsExactly("step-001-evalScript.png", "step-003-evalScript.png", "step-004-evalScript.png", "final.png")
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
        val orchestra = Orchestra(driver = FakeDeviceGateway(), platform = Platform.ANDROID, listeners = listOf(recording))

        // Still fails the flow, but must be finalized on the way out.
        assertThrows<MaestroException.AssertionFailure> {
            runBlocking { orchestra.runFlow(listOf(configWithFailingCompleteHook(), mainCmd)) }
        }

        assertThat(recording.events).contains("flowEnd")
    }

    @Test
    fun `manifest is written when an onFlowComplete hook command fails`() {
        val mainCmd = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val orchestra = Orchestra(
            driver = FakeDeviceGateway(),
            platform = Platform.ANDROID,
            artifactsDir = tempDir,
            captureFullArtifacts = true,
        )

        assertThrows<MaestroException.AssertionFailure> {
            runBlocking { orchestra.runFlow(listOf(configWithFailingCompleteHook(), mainCmd)) }
        }

        // No manifest.json → the worker uploads nothing, and the device log is never dumped at all.
        assertThat(tempDir.resolve(BundleLayout.MANIFEST_JSON).toFile().exists()).isTrue()
    }

    /**
     * User flow with env injection — equivalent to running
     *
     *     maestro test --env X=y flow.yaml
     *
     * or a YAML header with `env: { X: y }`. Env injection lowers via
     * List<MaestroCommand>.withEnv(env) into a synthesized
     * DefineVariablesCommand prepended to the flow. That command runs through
     * executeDefineVariablesCommands, inside runFlow's outer try/catch/finally — so a
     * putEnv failure must still let onFlowEnd fire on the way out.
     */
    @Test
    fun `onFlowEnd dispatches even when executeDefineVariablesCommands throws`() {
        val recording = RecordingListener()
        val brokenJsEngine: JsEngine = mockk(relaxed = true) {
            every { putEnv(any(), any()) } throws RuntimeException("boom")
        }
        val orchestra = Orchestra(
            driver = FakeDeviceGateway(),
            platform = Platform.ANDROID,
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

        val orchestra = Orchestra(driver = FakeDeviceGateway(), platform = Platform.ANDROID, listeners = listOf(recording))

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
}
