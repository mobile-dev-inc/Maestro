package maestro.orchestra.backend

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.Bounds
import maestro.FindElementResult
import maestro.Maestro
import maestro.MaestroException
import maestro.TreeNode
import maestro.UiElement
import maestro.ViewHierarchy
import maestro.orchestra.CopyTextFromCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.PasteTextCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Seam test for the input & clipboard cluster relocated into [LegacyExecutionBackend]. Proves the
 * two ADDITIVE interface fields thread correctly:
 *  - copyTextFrom RETURNS the extracted text via [CommandExecutionResult.output] (the router stores
 *    it in copiedText); no-text throws [MaestroException.UnableToCopyTextFromElement].
 *  - pasteText READS [BackendContext.copiedText] the router supplied and inputs it.
 */
class LegacyBackendInputTest {

    private fun context(copiedText: String? = null) = BackendContext(
        lookupTimeoutMs = 17000L,
        optionalLookupTimeoutMs = 7000L,
        timeMsOfLastInteraction = System.currentTimeMillis(),
        appId = "com.example.app",
        copiedText = copiedText,
    )

    @Test
    fun `execute CopyTextFromCommand returns extracted text as output and is non-mutating`() {
        val element = UiElement(
            TreeNode(attributes = mutableMapOf("text" to "Hello")),
            Bounds(x = 100, y = 200, width = 40, height = 20),
        )
        val canned = FindElementResult(element, ViewHierarchy(TreeNode()))

        val fakeMaestro: Maestro = mockk(relaxed = true)
        coEvery { fakeMaestro.findElementWithTimeout(timeoutMs = any(), filter = any()) } returns canned

        val backend = LegacyExecutionBackend(fakeMaestro)

        val command = CopyTextFromCommand(selector = ElementSelector(textRegex = "greeting"))

        val result = runBlocking { backend.execute(command, context()) }

        assertThat(result.mutating).isFalse()
        assertThat(result.output).isEqualTo("Hello")
    }

    @Test
    fun `execute CopyTextFromCommand throws UnableToCopyTextFromElement when element has no text`() {
        val element = UiElement(
            TreeNode(attributes = mutableMapOf()),
            Bounds(x = 100, y = 200, width = 40, height = 20),
        )
        val canned = FindElementResult(element, ViewHierarchy(TreeNode()))

        val fakeMaestro: Maestro = mockk(relaxed = true)
        coEvery { fakeMaestro.findElementWithTimeout(timeoutMs = any(), filter = any()) } returns canned

        val backend = LegacyExecutionBackend(fakeMaestro)

        val command = CopyTextFromCommand(selector = ElementSelector(textRegex = "greeting"))

        assertThrows<MaestroException.UnableToCopyTextFromElement> {
            runBlocking { backend.execute(command, context()) }
        }
    }

    @Test
    fun `execute PasteTextCommand inputs the copiedText the router supplied via context`() {
        val fakeMaestro: Maestro = mockk(relaxed = true)

        val backend = LegacyExecutionBackend(fakeMaestro)

        val result = runBlocking { backend.execute(PasteTextCommand(), context(copiedText = "Hi")) }

        assertThat(result.mutating).isTrue()
        coVerify { fakeMaestro.inputText("Hi") }
    }
}
