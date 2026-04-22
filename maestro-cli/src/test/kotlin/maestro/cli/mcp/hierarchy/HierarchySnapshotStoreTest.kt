package maestro.cli.mcp.hierarchy

import com.google.common.truth.Truth.assertThat
import maestro.TreeNode
import org.junit.jupiter.api.Test

class HierarchySnapshotStoreTest {

    @Test
    fun `stores the tree root for later matching`() {
        val store = HierarchySnapshotStore()
        val tree = node(attributes = mapOf("text" to "Sign in"))

        store.record("device-1", tree)

        assertThat(store.get("device-1")?.root).isSameInstanceAs(tree)
    }

    @Test
    fun `last write wins per device`() {
        val store = HierarchySnapshotStore()
        val first = node(attributes = mapOf("text" to "First"))
        val second = node(attributes = mapOf("text" to "Second"))

        store.record("device-1", first)
        store.record("device-1", second)

        assertThat(store.get("device-1")?.root).isSameInstanceAs(second)
    }

    @Test
    fun `get returns null for unknown device`() {
        val store = HierarchySnapshotStore()
        assertThat(store.get("nope")).isNull()
    }

    @Test
    fun `record ignores null root`() {
        val store = HierarchySnapshotStore()
        store.record("device-1", null)
        assertThat(store.get("device-1")).isNull()
    }

    private fun node(
        attributes: Map<String, String> = emptyMap(),
        children: List<TreeNode> = emptyList(),
    ): TreeNode = TreeNode(attributes = attributes.toMutableMap(), children = children)
}
