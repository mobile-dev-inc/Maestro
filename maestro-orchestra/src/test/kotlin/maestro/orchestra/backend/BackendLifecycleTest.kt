package maestro.orchestra.backend

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.ScreenRecording
import maestro.TreeNode
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.Orchestra
import okio.Sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Task K1 — proves Orchestra.runFlow closes the execution backend symmetrically with open(), so
 * device-core's on-device server doesn't leak per run. `backend.open()` was called at runFlow start
 * but nothing ever called `backend.close()`: `MaestroSession.close()` only closes `maestro`, which is
 * null on the device-core path, so `DeviceCoreExecutionBackend.close()` (which stops the on-device
 * server) never ran.
 */
class BackendLifecycleTest {

    /** Records open()/close() calls; optionally throws from execute() for a specific command. */
    private class FakeBackend(private val failOn: Command? = null) : ExecutionBackend {
        override val backendId: String = "legacy"

        var openCalled = false
        var closeCalled = false

        override fun open(appId: String?, config: MaestroConfig?) {
            openCalled = true
        }

        override fun close() {
            closeCalled = true
        }

        override suspend fun execute(command: Command, context: BackendContext): CommandExecutionResult {
            if (failOn != null && command == failOn) {
                throw RuntimeException("boom")
            }
            return CommandExecutionResult(mutating = true)
        }

        override fun hierarchySnapshot(): TreeNode? = TreeNode()

        override suspend fun evaluateCondition(
            condition: Condition?,
            commandOptional: Boolean,
            timeoutMs: Long?,
            context: BackendContext,
        ): Boolean = error("evaluateCondition is not exercised by BackendLifecycleTest")

        override suspend fun takeScreenshot(
            out: Sink,
            compressed: Boolean,
            cropOn: ElementSelector?,
            optional: Boolean,
            context: BackendContext?,
        ) = error("takeScreenshot is not exercised by BackendLifecycleTest")

        override suspend fun startScreenRecording(out: Sink): ScreenRecording =
            error("startScreenRecording is not exercised by BackendLifecycleTest")
    }

    @Test
    fun `runFlow opens and closes the backend on a successful flow`() {
        val backend = FakeBackend()
        val orchestra = Orchestra(maestro = mockk<Maestro>(relaxed = true), backend = backend)

        val result = runBlocking {
            orchestra.runFlow(listOf(MaestroCommand(launchAppCommand = LaunchAppCommand(appId = "com.example.app"))))
        }

        assertThat(result.success).isTrue()
        assertThat(backend.openCalled).isTrue()
        assertThat(backend.closeCalled).isTrue()
    }

    @Test
    fun `runFlow closes the backend even when the flow body throws`() {
        val launchApp = LaunchAppCommand(appId = "com.example.app")
        val backend = FakeBackend(failOn = launchApp)
        val orchestra = Orchestra(maestro = mockk<Maestro>(relaxed = true), backend = backend)

        assertThrows<RuntimeException> {
            runBlocking { orchestra.runFlow(listOf(MaestroCommand(launchAppCommand = launchApp))) }
        }

        assertThat(backend.openCalled).isTrue()
        assertThat(backend.closeCalled).isTrue()
    }
}
