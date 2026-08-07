package maestro.orchestra.backend

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.Bounds
import maestro.FindElementResult
import maestro.Maestro
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.UiElement
import maestro.ViewHierarchy
import maestro.orchestra.ElementSelector
import maestro.orchestra.SwipeCommand
import org.junit.jupiter.api.Test

/**
 * Seam test for the relocated swipe funnel in [LegacyExecutionBackend]. Proves an
 * element-relative [SwipeCommand] resolves its selector via the backend's own `findElement`
 * (backed by the fake Maestro's [Maestro.findElementWithTimeout]) and dispatches
 * [Maestro.swipe] with the resolved element's center point.
 */
class LegacyBackendSwipeTest {

    @Test
    fun `execute SwipeCommand with elementSelector resolves the element and swipes from its center`() {
        val element = UiElement(TreeNode(), Bounds(x = 100, y = 200, width = 40, height = 20))
        val hierarchy = ViewHierarchy(TreeNode())
        val canned = FindElementResult(element, hierarchy)

        val fakeMaestro: Maestro = mockk(relaxed = true)
        coEvery { fakeMaestro.findElementWithTimeout(timeoutMs = any(), filter = any()) } returns canned

        val backend = LegacyExecutionBackend(fakeMaestro)

        val command = SwipeCommand(
            elementSelector = ElementSelector(textRegex = "Login"),
            direction = SwipeDirection.UP,
        )
        val context = BackendContext(
            lookupTimeoutMs = 17000L,
            optionalLookupTimeoutMs = 7000L,
            timeMsOfLastInteraction = System.currentTimeMillis(),
            appId = "com.example.app",
        )

        val result = runBlocking { backend.execute(command, context) }

        assertThat(result.mutating).isTrue()
        coVerify {
            fakeMaestro.swipe(
                SwipeDirection.UP,
                element.bounds.center(),
                command.duration,
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs,
            )
        }
    }
}
