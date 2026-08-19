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
    fun `textMatches matches the error attribute`() {
        // Regression for #3515: a Compose field whose only content is its error semantics.
        val node = errorNode(error = "This is an error!")

        val result = Filters.textMatches(Regex("This is an error!"))(listOf(node))

        assertThat(result).containsExactly(node)
    }

    @Test
    fun `textMatches matches the error attribute by regex`() {
        val node = errorNode(error = "This is an error!")

        val result = Filters.textMatches(Regex("This is an.*"))(listOf(node))

        assertThat(result).containsExactly(node)
    }

    @Test
    fun `textMatches ignores a blank error attribute`() {
        // The Android hierarchy dump writes `error` for every node, empty when there is no error, so
        // an empty selector must not match on it. Only `error` is set here: the other text sources
        // already match an empty regex when present-but-empty, which would mask the guard.
        val node = TreeNode(attributes = mutableMapOf("error" to ""))

        val result = Filters.textMatches(Regex(""))(listOf(node))

        assertThat(result).isEmpty()
    }

    @Test
    fun `textMatches still matches text, hintText and accessibilityText`() {
        val textNode = TreeNode(attributes = mutableMapOf("text" to "Hello"))
        val hintNode = TreeNode(attributes = mutableMapOf("hintText" to "Hello"))
        val accessibilityNode = TreeNode(attributes = mutableMapOf("accessibilityText" to "Hello"))
        val errorNode = errorNode(error = "Hello")

        val result = Filters.textMatches(Regex("Hello"))(
            listOf(textNode, hintNode, accessibilityNode, errorNode)
        )

        assertThat(result).containsExactly(textNode, hintNode, accessibilityNode, errorNode)
    }

    @Test
    fun `textMatches does not match a node without the error`() {
        val node = errorNode(error = "This is an error!")

        val result = Filters.textMatches(Regex("Some other text"))(listOf(node))

        assertThat(result).isEmpty()
    }

    private fun errorNode(error: String): TreeNode {
        // Mirrors the Android hierarchy dump: the other text sources are present but empty.
        return TreeNode(
            attributes = mutableMapOf(
                "text" to "",
                "accessibilityText" to "",
                "hintText" to "",
                "error" to error,
            )
        )
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
