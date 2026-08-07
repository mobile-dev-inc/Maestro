package maestro.orchestra.backend

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.Maestro
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.Platform
import maestro.orchestra.Command
import maestro.orchestra.DefineVariablesCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.ScrollCommand
import org.junit.jupiter.api.Test

/**
 * Router-level seam test: proves Orchestra.executeCommand's above/below split. Below-seam,
 * relocated commands (e.g. LaunchAppCommand) must reach the [ExecutionBackend]; above-seam
 * commands (flow control / variables, e.g. DefineVariablesCommand) must never reach it — they
 * stay entirely inside Orchestra. A still-in-Orchestra below-seam command that hasn't been
 * relocated yet (ScrollCommand) is included too, to show the backend only sees what's actually
 * been routed to it, not everything below the seam.
 */
class RouterSeamTest {

    /** Records every command handed to [execute] so the test can assert real dispatch, not just a stub return. */
    private class RecordingBackend : ExecutionBackend {
        val executed = mutableListOf<Command>()

        override fun open(appId: String?) {}
        override fun close() {}

        override suspend fun execute(command: Command, context: BackendContext): CommandExecutionResult {
            executed.add(command)
            return CommandExecutionResult(mutating = true)
        }

        override fun viewHierarchy(excludeKeyboardElements: Boolean): ViewHierarchy = ViewHierarchy(TreeNode())

        override val deviceInfo: DeviceInfo
            get() = DeviceInfo(
                platform = Platform.ANDROID,
                widthPixels = 1080,
                heightPixels = 1920,
                widthGrid = 1080,
                heightGrid = 1920,
            )
    }

    @Test
    fun `relocated commands reach the backend, above-seam commands never do`() {
        val recordingBackend = RecordingBackend()
        val fakeMaestro: Maestro = mockk(relaxed = true)
        val orchestra = Orchestra(
            maestro = fakeMaestro,
            backend = recordingBackend,
        )

        val launchApp = LaunchAppCommand(appId = "com.example.app")
        val scroll = ScrollCommand()
        val defineVariables = DefineVariablesCommand(env = mapOf("X" to "y"))

        val flow = listOf(
            MaestroCommand(launchAppCommand = launchApp),
            MaestroCommand(scrollCommand = scroll),
            MaestroCommand(defineVariablesCommand = defineVariables),
        )

        runBlocking { orchestra.runFlow(flow) }

        // Below-seam + relocated: reaches the backend.
        assertThat(recordingBackend.executed).contains(launchApp)
        // Above-seam: flow control/variables never reach the backend.
        assertThat(recordingBackend.executed).doesNotContain(defineVariables)
        // Below-seam but not yet relocated: also doesn't reach the backend (still handled by
        // Orchestra directly) — the backend only sees what's actually been routed to it.
        assertThat(recordingBackend.executed).doesNotContain(scroll)
    }
}
