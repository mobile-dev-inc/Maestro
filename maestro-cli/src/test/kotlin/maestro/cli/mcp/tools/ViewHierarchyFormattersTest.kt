package maestro.cli.mcp.tools

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.truth.Truth.assertThat
import maestro.TreeNode
import org.junit.jupiter.api.Test

class ViewHierarchyFormattersTest {

    @Test
    fun `compact json emits ui_schema and elements with android abbreviations`() {
        val tree = TreeNode(
            attributes = mutableMapOf("bounds" to "[0,0][100,100]"),
            children = listOf(
                TreeNode(
                    attributes = mutableMapOf(
                        "bounds" to "[10,10][90,50]",
                        "text" to "Submit",
                        "resource-id" to "btn_submit",
                    ),
                    clickable = true,
                ),
            ),
        )

        val json = ViewHierarchyFormatters.extractCompactJsonOutput(tree, "android")
        val parsed: Map<String, Any?> = jacksonObjectMapper().readValue(json)

        @Suppress("UNCHECKED_CAST")
        val schema = parsed["ui_schema"] as Map<String, Any?>
        assertThat(schema["platform"]).isEqualTo("android")
        @Suppress("UNCHECKED_CAST")
        assertThat((schema["abbreviations"] as Map<String, Any?>)["rid"]).isEqualTo("resource-id")

        @Suppress("UNCHECKED_CAST")
        val elements = parsed["elements"] as List<Map<String, Any?>>
        assertThat(elements).hasSize(1)
        val root = elements[0]
        assertThat(root["b"]).isEqualTo("[0,0][100,100]")
        @Suppress("UNCHECKED_CAST")
        val children = root["c"] as List<Map<String, Any?>>
        assertThat(children).hasSize(1)
        val submit = children[0]
        assertThat(submit["txt"]).isEqualTo("Submit")
        assertThat(submit["rid"]).isEqualTo("btn_submit")
        assertThat(submit["clickable"]).isEqualTo(true)
        // NON_NULL: keys with no value are omitted
        assertThat(submit).doesNotContainKey("val")
        assertThat(submit).doesNotContainKey("a11y")
    }

    @Test
    fun `compact json uses ios schema for ios platform`() {
        val tree = TreeNode(
            attributes = mutableMapOf(
                "bounds" to "[0,0][50,50]",
                "value" to "Hello",
            ),
        )

        val json = ViewHierarchyFormatters.extractCompactJsonOutput(tree, "ios")
        val parsed: Map<String, Any?> = jacksonObjectMapper().readValue(json)

        @Suppress("UNCHECKED_CAST")
        val schema = parsed["ui_schema"] as Map<String, Any?>
        assertThat(schema["platform"]).isEqualTo("ios")
        @Suppress("UNCHECKED_CAST")
        assertThat((schema["abbreviations"] as Map<String, Any?>)).containsKey("val")

        @Suppress("UNCHECKED_CAST")
        val elements = parsed["elements"] as List<Map<String, Any?>>
        assertThat(elements).hasSize(1)
        assertThat(elements[0]["val"]).isEqualTo("Hello")
    }

    @Test
    fun `unknown platform falls back to ios schema`() {
        val tree = TreeNode(attributes = mutableMapOf("bounds" to "[0,0][10,10]", "text" to "x"))

        val json = ViewHierarchyFormatters.extractCompactJsonOutput(tree, "web")
        val parsed: Map<String, Any?> = jacksonObjectMapper().readValue(json)

        @Suppress("UNCHECKED_CAST")
        assertThat((parsed["ui_schema"] as Map<String, Any?>)["platform"]).isEqualTo("ios")
    }

    @Test
    fun `zero-size nodes are filtered out`() {
        val tree = TreeNode(
            attributes = mutableMapOf("bounds" to "[0,0][0,0]", "text" to "Invisible"),
        )

        val json = ViewHierarchyFormatters.extractCompactJsonOutput(tree, "android")
        val parsed: Map<String, Any?> = jacksonObjectMapper().readValue(json)

        @Suppress("UNCHECKED_CAST")
        val elements = parsed["elements"] as List<Map<String, Any?>>
        assertThat(elements).isEmpty()
    }
}
