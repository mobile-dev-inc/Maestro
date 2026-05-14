package maestro.drivers

import com.google.common.truth.Truth.assertThat
import maestro.TreeNode
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.openqa.selenium.NoSuchFrameException
import org.openqa.selenium.NoSuchWindowException
import org.openqa.selenium.StaleElementReferenceException

class WebHierarchyTest {

    @Test
    fun `null iframe content result does not crash`() {
        val tree = injectIframeContent {
            WebHierarchy.parseDomJson("null", "cross-origin iframe")
        }

        assertParentHierarchyReturned(tree)
        assertThat(tree.children[1].attributes["text"]).isEqualTo("")
        assertThat(tree.children[1].children).isEmpty()
    }

    @Test
    fun `malformed iframe content result does not crash`() {
        val tree = injectIframeContent {
            mapOf(
                "attributes" to emptyMap<String, Any>(),
                "children" to emptyList<Map<String, Any>>(),
            )
        }

        assertParentHierarchyReturned(tree)
        assertThat(tree.children[1].attributes["text"]).isEqualTo("")
        assertThat(tree.children[1].children).isEmpty()
    }

    @ParameterizedTest
    @MethodSource("transientIframeErrors")
    fun `transient iframe fetch errors are swallowed and traversal continues`(exception: Exception) {
        val tree = injectIframeContent {
            throw exception
        }

        assertParentHierarchyReturned(tree)
        assertThat(tree.children[1].attributes["text"]).isEqualTo("")
        assertThat(tree.children[1].children).isEmpty()
    }

    @Test
    fun `readable iframe content is preserved`() {
        val tree = injectIframeContent {
            iframeContent()
        }

        assertParentHierarchyReturned(tree)
        assertThat(tree.children[1].attributes["text"]).isEqualTo("Support chat")
        assertThat(tree.children[1].children).hasSize(1)
        assertThat(tree.children[1].children[0].attributes["resource-id"]).isEqualTo("chat-input")
    }

    private fun injectIframeContent(fetchIframeContent: (String) -> Map<String, Any>?): TreeNode {
        val normalized = requireNotNull(WebHierarchy.normalizeDomNode(parentHierarchy(), "test parent hierarchy"))
        val enriched = WebHierarchy.injectCrossOriginIframes(normalized, fetchIframeContent)
        return WebHierarchy.parseDomAsTreeNodes(enriched)
    }

    private fun assertParentHierarchyReturned(tree: TreeNode) {
        assertThat(tree.attributes["text"]).isEqualTo("Parent page")
        assertThat(tree.children).hasSize(2)
        assertThat(tree.children[0].attributes["text"]).isEqualTo("Parent button")
        assertThat(tree.children[0].attributes["resource-id"]).isEqualTo("parent-button")
    }

    private fun parentHierarchy(): Map<String, Any> {
        return mapOf(
            "attributes" to mapOf(
                "text" to "Parent page",
                "bounds" to "[0,0][400,800]",
            ),
            "children" to listOf(
                mapOf(
                    "attributes" to mapOf(
                        "text" to "Parent button",
                        "bounds" to "[10,10][120,60]",
                        "resource-id" to "parent-button",
                    ),
                    "children" to emptyList<Map<String, Any>>(),
                ),
                mapOf(
                    "attributes" to mapOf(
                        "text" to "",
                        "bounds" to "[20,80][380,700]",
                        "__crossOriginIframe" to IFRAME_SRC,
                    ),
                    "children" to emptyList<Map<String, Any>>(),
                ),
            ),
        )
    }

    private fun iframeContent(): Map<String, Any> {
        return mapOf(
            "attributes" to mapOf(
                "text" to "Support chat",
                "bounds" to "[20,80][380,700]",
            ),
            "children" to listOf(
                mapOf(
                    "attributes" to mapOf(
                        "text" to "Message",
                        "bounds" to "[40,600][360,680]",
                        "resource-id" to "chat-input",
                    ),
                    "children" to emptyList<Map<String, Any>>(),
                ),
            ),
        )
    }

    companion object {
        private const val IFRAME_SRC = "https://support.example/chat"

        @JvmStatic
        fun transientIframeErrors(): List<Exception> {
            return listOf(
                StaleElementReferenceException("iframe is stale"),
                NoSuchFrameException("iframe is gone"),
                NoSuchWindowException("window is closed"),
            )
        }
    }
}
