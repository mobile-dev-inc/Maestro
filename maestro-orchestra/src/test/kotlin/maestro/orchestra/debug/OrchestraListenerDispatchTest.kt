package maestro.orchestra.debug

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.Maestro
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.Platform
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import org.junit.jupiter.api.Test

class OrchestraListenerDispatchTest {

    private fun mockMaestro(): Maestro = mockk(relaxed = true) {
        coEvery { viewHierarchy(any()) } returns ViewHierarchy(TreeNode(attributes = mutableMapOf()))
        coEvery { cachedDeviceInfo } returns DeviceInfo(
            platform = Platform.ANDROID,
            widthPixels = 100,
            heightPixels = 200,
            widthGrid = 100,
            heightGrid = 200,
        )
    }

    private class RecordingListener : OrchestraListener {
        val events = mutableListOf<String>()
        override fun onFlowStart() { events.add("flowStart") }
        override fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int) {
            events.add("commandStart:$sequenceNumber")
        }
        override fun onCommandFinished(
            cmd: MaestroCommand,
            outcome: CommandOutcome,
            startedAt: Long,
            finishedAt: Long,
        ) {
            events.add("commandFinished:${outcome::class.simpleName}")
        }
        override fun onFlowEnd() { events.add("flowEnd") }
    }

    @Test
    fun `listener and legacy lambdas both fire on the same lifecycle events`() {
        val legacyEvents = mutableListOf<String>()
        val recording = RecordingListener()

        val orchestra = Orchestra(
            maestro = mockMaestro(),
            listeners = listOf(recording),
            onFlowStart = { legacyEvents.add("legacyFlowStart") },
            onCommandStart = { _, _ -> legacyEvents.add("legacyCommandStart") },
            onCommandComplete = { _, _ -> legacyEvents.add("legacyCommandComplete") },
        )

        // Run an empty flow — exercises onFlowStart + onFlowEnd only. Listener
        // dispatch on per-command events is exercised in ArtifactsGeneratorTest.
        runBlocking { orchestra.runFlow(emptyList()) }

        assertThat(recording.events).containsAtLeast("flowStart", "flowEnd").inOrder()
        assertThat(legacyEvents).contains("legacyFlowStart")
    }

    @Test
    fun `orchestra exposes debugOutput populated by the internal ArtifactsGenerator`() {
        val orchestra = Orchestra(maestro = mockMaestro())

        runBlocking { orchestra.runFlow(emptyList()) }

        // Empty flow: no commands populated, but debugOutput exists and is readable.
        assertThat(orchestra.debugOutput.commands).isEmpty()
    }
}
