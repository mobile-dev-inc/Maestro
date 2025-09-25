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
