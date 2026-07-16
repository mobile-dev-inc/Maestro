package maestro

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class FiltersTest {

    @Test
    fun `index returns element at positive position`() {
        val nodes = sampleNodes()

        val result = Filters.index(1)(nodes)

        assertThat(result).containsExactly(nodes[1])
    }

    @Test
    fun `index supports negative values`() {
        val nodes = sampleNodes()

        val result = Filters.index(-1)(nodes)

        assertThat(result).containsExactly(nodes.last())
    }

    @Test
    fun `index supports negative value matching collection size`() {
        val nodes = sampleNodes()

        val result = Filters.index(-nodes.size)(nodes)

        assertThat(result).containsExactly(nodes.first())
    }

    @Test
    fun `index returns empty when negative value exceeds bounds`() {
        val nodes = sampleNodes()

        val result = Filters.index(-4)(nodes)

        assertThat(result).isEmpty()
    }

    @Test
    fun `textMatches finds node by accessibilityText`() {
        val button = TreeNode(attributes = mutableMapOf(
            "text" to "",
            "accessibilityText" to "Add to favourites",
        ))
        val nodes = listOf(button)

        val result = Filters.textMatches(Regex("Add to favourites"))(nodes)

        assertThat(result).containsExactly(button)
    }

    @Test
    fun `textMatches finds node by text even when accessibilityText differs`() {
        val button = TreeNode(attributes = mutableMapOf(
            "text" to "Click me",
            "accessibilityText" to "Submit button",
        ))

        val result = Filters.textMatches(Regex("Click me"))(listOf(button))

        assertThat(result).containsExactly(button)
    }

    @Test
    fun `textMatches returns union of text and accessibilityText matches`() {
        val byText = TreeNode(attributes = mutableMapOf("text" to "Search", "bounds" to "0,0,1,1"))
        val byLabel = TreeNode(attributes = mutableMapOf(
            "text" to "",
            "accessibilityText" to "Search",
            "bounds" to "0,0,2,2",
        ))
        val noMatch = TreeNode(attributes = mutableMapOf("text" to "Cancel", "bounds" to "0,0,3,3"))

        val result = Filters.textMatches(Regex("Search"))(listOf(byText, byLabel, noMatch))

        assertThat(result).containsExactly(byText, byLabel)
    }

    @Test
    fun `textMatches does not duplicate when text and accessibilityText both match`() {
        val button = TreeNode(attributes = mutableMapOf(
            "text" to "Save",
            "accessibilityText" to "Save",
        ))

        val result = Filters.textMatches(Regex("Save"))(listOf(button))

        assertThat(result).containsExactly(button)
    }

    @Test
    fun `textMatches does not match accessibilityText absent`() {
        val button = TreeNode(attributes = mutableMapOf("text" to ""))

        val result = Filters.textMatches(Regex("Add to favourites"))(listOf(button))

        assertThat(result).isEmpty()
    }

    @Test
    fun `idMatches does not match accessibilityText alone`() {
        val button = TreeNode(attributes = mutableMapOf(
            "text" to "",
            "accessibilityText" to "some-label",
        ))

        val result = Filters.idMatches(Regex("some-label"))(listOf(button))

        assertThat(result).isEmpty()
    }

    @Test
    fun `idMatches finds node by resource-id when accessibilityText also present`() {
        val button = TreeNode(attributes = mutableMapOf(
            "text" to "",
            "resource-id" to "test-id",
            "accessibilityText" to "some-label",
        ))

        val result = Filters.idMatches(Regex("test-id"))(listOf(button))

        assertThat(result).containsExactly(button)
    }

    @Test
    fun `idMatches finds aria-label as resource-id fallback`() {
        val button = TreeNode(attributes = mutableMapOf(
            "text" to "",
            "resource-id" to "Add to favourites",
            "accessibilityText" to "Add to favourites",
        ))

        val result = Filters.idMatches(Regex("Add to favourites"))(listOf(button))

        assertThat(result).containsExactly(button)
    }

    @Test
    fun `idMatches prefers data-testid over aria-label in resource-id`() {
        val button = TreeNode(attributes = mutableMapOf(
            "text" to "",
            "resource-id" to "submit-btn",
            "accessibilityText" to "Submit form",
        ))

        val result = Filters.idMatches(Regex("Submit form"))(listOf(button))

        assertThat(result).isEmpty()
    }

    private fun sampleNodes(): List<TreeNode> {
        return listOf(
            node(bounds(0, 0)),
            node(bounds(10, 10)),
            node(bounds(20, 20)),
        )
    }

    private fun node(bounds: String): TreeNode {
        return TreeNode(attributes = mutableMapOf("bounds" to bounds))
    }

    private fun bounds(x: Int, y: Int): String {
        val size = 5
        return "[${x},${y}][${x + size},${y + size}]"
    }
}
