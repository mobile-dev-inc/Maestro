package maestro.cli.mcp.hierarchy

import com.google.common.truth.Truth.assertThat
import maestro.TreeNode
import org.junit.jupiter.api.Test

class HierarchySnapshotStoreTest {

    @Test
    fun `records text-bearing attributes from the tree`() {
        val store = HierarchySnapshotStore()
        val tree = node(
            attributes = mapOf("text" to "Sign in", "bounds" to "[0,0][100,50]"),
            children = listOf(
                node(attributes = mapOf("accessibilityText" to "Favorite button")),
                node(attributes = mapOf("content-desc" to "Share", "hintText" to "Tap to share")),
            ),
        )

        store.record("device-1", tree)

        val snapshot = store.get("device-1")
        assertThat(snapshot).isNotNull()
        assertThat(snapshot!!.texts).containsExactly(
            "Sign in", "Favorite button", "Share", "Tap to share",
        )
    }

    @Test
    fun `last write wins per device`() {
        val store = HierarchySnapshotStore()
        store.record("device-1", node(attributes = mapOf("text" to "First")))
        store.record("device-1", node(attributes = mapOf("text" to "Second")))

        assertThat(store.get("device-1")!!.texts).containsExactly("Second")
    }

    @Test
    fun `get returns null for unknown device`() {
        val store = HierarchySnapshotStore()
        assertThat(store.get("nope")).isNull()
    }

    @Test
    fun `null or empty attribute values are skipped`() {
        val store = HierarchySnapshotStore()
        store.record("device-1", node(attributes = mapOf("text" to "", "accessibilityText" to "   ")))
        assertThat(store.get("device-1")!!.texts).isEmpty()
    }

    private fun node(
        attributes: Map<String, String> = emptyMap(),
        children: List<TreeNode> = emptyList(),
    ): TreeNode = TreeNode(attributes = attributes.toMutableMap(), children = children)
}
