package maestro.cli.mcp.hierarchy

import com.google.common.truth.Truth.assertThat
import maestro.TreeNode
import org.junit.jupiter.api.Test

class HierarchySnapshotStoreTest {

    @Test
    fun `stores the tree root`() {
        val store = HierarchySnapshotStore()
        val tree = TreeNode(attributes = mutableMapOf("text" to "Sign in"))
        store.record("device-1", tree)
        assertThat(store.get("device-1")?.root).isSameInstanceAs(tree)
    }

    @Test
    fun `last write wins per device`() {
        val store = HierarchySnapshotStore()
        val first = TreeNode(attributes = mutableMapOf("text" to "First"))
        val second = TreeNode(attributes = mutableMapOf("text" to "Second"))
        store.record("device-1", first)
        store.record("device-1", second)
        assertThat(store.get("device-1")?.root).isSameInstanceAs(second)
    }

    @Test
    fun `unknown device returns null`() {
        assertThat(HierarchySnapshotStore().get("nope")).isNull()
    }
}
