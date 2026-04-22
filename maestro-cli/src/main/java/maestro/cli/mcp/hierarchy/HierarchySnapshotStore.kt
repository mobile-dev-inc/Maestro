package maestro.cli.mcp.hierarchy

import maestro.TreeNode
import java.util.concurrent.ConcurrentHashMap

class HierarchySnapshotStore {

    data class Snapshot(val root: TreeNode)

    private val snapshots = ConcurrentHashMap<String, Snapshot>()

    fun record(deviceId: String, root: TreeNode?) {
        if (root == null) return
        snapshots[deviceId] = Snapshot(root)
    }

    fun get(deviceId: String): Snapshot? = snapshots[deviceId]

    fun clear() {
        snapshots.clear()
    }
}
