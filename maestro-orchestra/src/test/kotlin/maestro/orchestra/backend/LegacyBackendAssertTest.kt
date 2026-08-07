package maestro.orchestra.backend

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.Bounds
import maestro.FindElementResult
import maestro.Maestro
import maestro.MaestroException
import maestro.TreeNode
import maestro.UiElement
import maestro.ViewHierarchy
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.AssertDarkModeCommand
import maestro.orchestra.AssertLightModeCommand
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Seam test for the assert cluster relocated into [LegacyExecutionBackend]. Proves the relocated
 * assert funnel routes through the backend and preserves today's Orchestra behavior: a `visible`
 * condition whose element resolves passes (non-mutating, no throw); a `visible` condition whose
 * element cannot be found throws [MaestroException.AssertionFailure] (via evaluateCondition's
 * ElementNotFound catch returning false).
 */
class LegacyBackendAssertTest {

    private fun context() = BackendContext(
        lookupTimeoutMs = 17000L,
        optionalLookupTimeoutMs = 7000L,
        timeMsOfLastInteraction = System.currentTimeMillis(),
        appId = "com.example.app",
    )

    @Test
    fun `execute AssertConditionCommand passes when visible element resolves`() {
        val element = UiElement(TreeNode(), Bounds(x = 100, y = 200, width = 40, height = 20))
        val hierarchy = ViewHierarchy(TreeNode())
        val canned = FindElementResult(element, hierarchy)

        val fakeMaestro: Maestro = mockk(relaxed = true)
        coEvery { fakeMaestro.findElementWithTimeout(timeoutMs = any(), filter = any()) } returns canned

        val backend = LegacyExecutionBackend(fakeMaestro)

        val command = AssertConditionCommand(
            condition = Condition(visible = ElementSelector(textRegex = "Login")),
        )

        val result = runBlocking { backend.execute(command, context()) }

        assertThat(result.mutating).isFalse()
    }

    @Test
    fun `execute AssertConditionCommand throws AssertionFailure when visible element not found`() {
        val fakeMaestro: Maestro = mockk(relaxed = true)
        coEvery { fakeMaestro.findElementWithTimeout(timeoutMs = any(), filter = any()) } returns null
        coEvery { fakeMaestro.viewHierarchy(any()) } returns ViewHierarchy(TreeNode())

        val backend = LegacyExecutionBackend(fakeMaestro)

        val command = AssertConditionCommand(
            condition = Condition(visible = ElementSelector(textRegex = "Login")),
        )

        assertThrows<MaestroException.AssertionFailure> {
            runBlocking { backend.execute(command, context()) }
        }
    }

    @Test
    fun `execute AssertLightModeCommand routes to the shared assertDarkMode helper`() {
        val fakeMaestro: Maestro = mockk(relaxed = true)
        coEvery { fakeMaestro.isDarkModeEnabled() } returns false

        val backend = LegacyExecutionBackend(fakeMaestro)

        val result = runBlocking { backend.execute(AssertLightModeCommand(), context()) }

        assertThat(result.mutating).isFalse()
    }

    @Test
    fun `execute AssertLightModeCommand throws AssertionFailure when dark mode is enabled`() {
        val fakeMaestro: Maestro = mockk(relaxed = true)
        coEvery { fakeMaestro.isDarkModeEnabled() } returns true
        coEvery { fakeMaestro.viewHierarchy(any()) } returns ViewHierarchy(TreeNode())

        val backend = LegacyExecutionBackend(fakeMaestro)

        assertThrows<MaestroException.AssertionFailure> {
            runBlocking { backend.execute(AssertLightModeCommand(), context()) }
        }
    }

    @Test
    fun `execute AssertDarkModeCommand still passes when dark mode is enabled`() {
        val fakeMaestro: Maestro = mockk(relaxed = true)
        coEvery { fakeMaestro.isDarkModeEnabled() } returns true

        val backend = LegacyExecutionBackend(fakeMaestro)

        val result = runBlocking { backend.execute(AssertDarkModeCommand(), context()) }

        assertThat(result.mutating).isFalse()
    }
}
