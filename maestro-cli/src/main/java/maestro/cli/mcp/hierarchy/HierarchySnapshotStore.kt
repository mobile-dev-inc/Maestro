package maestro.cli.mcp.hierarchy

import maestro.TreeNode
import java.util.concurrent.ConcurrentHashMap

/**
 * Remembers the most recent view-hierarchy snapshot per device so selector
 * validation in [maestro.cli.mcp.tools.RunTool] can check `text:` values
 * against what's actually on screen.
 *
 * `inspect_view_hierarchy` writes here; `run` reads. Snapshots live only in
 * process memory, last-write-wins, and are never persisted across restarts.
 */
class HierarchySnapshotStore {

    data class Snapshot(
        val deviceId: String,
        val texts: Set<String>,
    )

    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    fun record(deviceId: String, root: TreeNode?) {
        val texts = collectTexts(root)
        snapshots[deviceId] = Snapshot(deviceId = deviceId, texts = texts)
    }

    fun get(deviceId: String): Snapshot? = snapshots[deviceId]

    fun clear() {
        snapshots.clear()
    }

    private fun collectTexts(root: TreeNode?): Set<String> {
        if (root == null) return emptySet()
        val out = linkedSetOf<String>()
        walk(root, out)
        return out
    }

    private fun walk(node: TreeNode, out: MutableSet<String>) {
        TEXT_BEARING_KEYS.forEach { key ->
            node.attributes[key]
                ?.takeIf { it.isNotBlank() }
                ?.let { out.add(it) }
        }
        node.children.forEach { walk(it, out) }
    }

    companion object {
        // Every attribute a Maestro `text:` selector might plausibly match
        // against. Sourced from the same fields the CSV/compact formatters
        // emit for TreeNode.attributes.
        private val TEXT_BEARING_KEYS = listOf(
            "text",
            "accessibilityText",
            "content-desc",
            "hintText",
            "value",
            "label",
            "title",
            "placeholder",
        )
    }
}
