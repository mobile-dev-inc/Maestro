package maestro.orchestra.debug

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.KeyCode
import maestro.Maestro
import maestro.MaestroException
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.Platform
import maestro.orchestra.EvalScriptCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.RepeatCommand
import maestro.orchestra.RetryCommand
import maestro.orchestra.RunFlowCommand
import org.junit.jupiter.api.Test

class OrchestraListenerDispatchTest {

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

        val events = mutableListOf<String>()
        val started = mutableListOf<MaestroCommand>()
        val finished = mutableListOf<FinishedEvent>()
        val resets = mutableListOf<MaestroCommand>()

        override fun onFlowStart() { events.add("flowStart") }
        override fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int) {
            events.add("commandStart:$sequenceNumber")
            started.add(cmd)
        }
        override fun onCommandFinished(
            cmd: MaestroCommand,
            outcome: CommandOutcome,
            startedAt: Long,
            finishedAt: Long,
        ) {
            events.add("commandFinished:${outcome::class.simpleName}")
            finished.add(FinishedEvent(cmd, outcome::class.simpleName!!))
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
}
