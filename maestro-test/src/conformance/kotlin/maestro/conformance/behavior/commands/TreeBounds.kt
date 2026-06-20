package maestro.conformance.behavior.commands

import maestro.TreeNode

data class Bounds(val l: Int, val t: Int, val r: Int, val b: Int) {
    val centerX get() = (l + r) / 2
    val centerY get() = (t + b) / 2
}

object TreeBounds {
    /** Find a node whose resource-id/accessibility-text matches [id], return its pixel bounds. */
    fun find(node: TreeNode, id: String): Bounds? {
        val attrs = node.attributes
        val matches = attrs["resource-id"]?.endsWith(id) == true ||
            attrs["text"] == id || attrs["accessibilityText"] == id ||
            attrs["content-desc"] == id || attrs["hintText"] == id
        if (matches) parseBounds(attrs["bounds"])?.let { return it }
        for (child in node.children) find(child, id)?.let { return it }
        return null
    }

    // Android dumps bounds as "[l,t][r,b]".
    private fun parseBounds(s: String?): Bounds? {
        if (s == null) return null
        val m = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""").find(s) ?: return null
        val (l, t, r, b) = m.destructured
        return Bounds(l.toInt(), t.toInt(), r.toInt(), b.toInt())
    }
}
