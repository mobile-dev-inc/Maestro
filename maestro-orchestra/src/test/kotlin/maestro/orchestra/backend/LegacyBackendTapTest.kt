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
import maestro.orchestra.ElementSelector
import maestro.orchestra.TapOnElementCommand
import org.junit.jupiter.api.Test

/**
 * Seam test for [LegacyExecutionBackend]. Proves the relocated tap funnel routes through the
 * backend and preserves today's Orchestra behavior: a resolved element is tapped, and the result
 * reports the command mutated device state. The fake Maestro's [Maestro.findElementWithTimeout]
 * returns a canned element with bounds (100, 200, 40, 20); the test verifies [Maestro.tap] was
 * invoked with exactly that element and that result.mutating == true.
 */
class LegacyBackendTapTest {

    @Test
    fun `execute TapOnElementCommand taps the resolved element and reports mutating`() {
        val element = UiElement(TreeNode(), Bounds(x = 100, y = 200, width = 40, height = 20))
        val hierarchy = ViewHierarchy(TreeNode())
        val canned = FindElementResult(element, hierarchy)

        val fakeMaestro: Maestro = mockk(relaxed = true)
        coEvery { fakeMaestro.findElementWithTimeout(timeoutMs = any(), filter = any()) } returns canned

        val backend = LegacyExecutionBackend(fakeMaestro)

        val command = TapOnElementCommand(selector = ElementSelector(textRegex = "Login"))
        val context = BackendContext(
            lookupTimeoutMs = 17000L,
            optionalLookupTimeoutMs = 7000L,
            timeMsOfLastInteraction = System.currentTimeMillis(),
            appId = "com.example.app",
        )

        val result = runBlocking { backend.execute(command, context) }

        assertThat(result.mutating).isTrue()
        coVerify {
            fakeMaestro.tap(
                element = element,
                initialHierarchy = hierarchy,
                retryIfNoChange = any(),
                waitUntilVisible = any(),
                longPress = any(),
                appId = any(),
                tapRepeat = any(),
                waitToSettleTimeoutMs = any(),
            )
        }
    }
}
