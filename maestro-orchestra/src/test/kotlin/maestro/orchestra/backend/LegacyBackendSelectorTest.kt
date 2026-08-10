package maestro.orchestra.backend

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.Bounds
import maestro.FindElementResult
import maestro.Maestro
import maestro.TreeNode
import maestro.UiElement
import maestro.ViewHierarchy
import maestro.device.CapturedDeviceArtifact
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Seam test for the selector-resolution surface. `evaluateCondition` is an [ExecutionBackend]
 * interface method (the sole implementation lives in [LegacyExecutionBackend]; Orchestra's private
 * duplicate was deleted). `findElement` was demoted to a private helper in Task 4.0 (it no longer
 * crosses the seam), so it is now exercised only INDIRECTLY — through `evaluateCondition`'s
 * visible/notVisible resolution here, and through the `takeScreenshot` crop path elsewhere. The
 * `backend` is typed as [ExecutionBackend] on purpose — these tests only compile against the seam.
 */
class LegacyBackendSelectorTest {

    private fun context() = BackendContext(
        lookupTimeoutMs = 17000L,
        optionalLookupTimeoutMs = 7000L,
        timeMsOfLastInteraction = System.currentTimeMillis(),
        appId = "com.example.app",
    )

    @Test
    fun `evaluateCondition returns true when the visible element resolves`() {
        val element = UiElement(TreeNode(), Bounds(x = 100, y = 200, width = 40, height = 20))
        val canned = FindElementResult(element, ViewHierarchy(TreeNode()))

        val fakeMaestro: Maestro = mockk(relaxed = true)
        coEvery { fakeMaestro.findElementWithTimeout(timeoutMs = any(), filter = any()) } returns canned

        val backend: ExecutionBackend = LegacyExecutionBackend(fakeMaestro)

        val result = runBlocking {
            backend.evaluateCondition(
                condition = Condition(visible = ElementSelector(textRegex = "Login")),
                commandOptional = false,
                timeoutMs = null,
                context = context(),
            )
        }

        assertThat(result).isTrue()
    }

    @Test
    fun `evaluateCondition returns false when the visible element is not found`() {
        val fakeMaestro: Maestro = mockk(relaxed = true)
        coEvery { fakeMaestro.findElementWithTimeout(timeoutMs = any(), filter = any()) } returns null
        coEvery { fakeMaestro.viewHierarchy(any()) } returns ViewHierarchy(TreeNode())

        val backend: ExecutionBackend = LegacyExecutionBackend(fakeMaestro)

        val result = runBlocking {
            backend.evaluateCondition(
                condition = Condition(visible = ElementSelector(textRegex = "Login")),
                commandOptional = false,
                timeoutMs = null,
                context = context(),
            )
        }

        assertThat(result).isFalse()
    }

    // --- device-log / crash capture delegation (Task 4.D2-T2): LegacyExecutionBackend forwards
    // verbatim to its Maestro, so legacy artifact capture stays byte-identical behind the seam. ---

    @Test
    fun `device-log and crash capture delegate to the underlying Maestro`() {
        val fakeMaestro: Maestro = mockk(relaxed = true)
        val outputDir = File("build/tmp/legacy-backend-selector-test")
        val logArtifact = CapturedDeviceArtifact(type = CapturedDeviceArtifact.Type.DEVICE_LOG, file = File(outputDir, "device.log"))
        val crashArtifact = CapturedDeviceArtifact(type = CapturedDeviceArtifact.Type.CRASH_REPORT, file = File(outputDir, "crash.txt"))
        coEvery { fakeMaestro.stopAndCollectDeviceLogs(outputDir) } returns listOf(logArtifact)
        coEvery { fakeMaestro.collectCrashArtifacts("com.example.app", 123L, outputDir) } returns listOf(crashArtifact)

        val backend: ExecutionBackend = LegacyExecutionBackend(fakeMaestro)

        runBlocking { backend.startDeviceLogCapture() }
        val logs = runBlocking { backend.stopAndCollectDeviceLogs(outputDir) }
        val crashes = runBlocking { backend.collectCrashArtifacts("com.example.app", 123L, outputDir) }

        coVerify(exactly = 1) { fakeMaestro.startDeviceLogCapture() }
        coVerify(exactly = 1) { fakeMaestro.stopAndCollectDeviceLogs(outputDir) }
        coVerify(exactly = 1) { fakeMaestro.collectCrashArtifacts("com.example.app", 123L, outputDir) }
        assertThat(logs).containsExactly(logArtifact)
        assertThat(crashes).containsExactly(crashArtifact)
    }
}
