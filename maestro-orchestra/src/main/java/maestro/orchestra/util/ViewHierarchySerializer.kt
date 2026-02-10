package maestro.orchestra.util

import maestro.TreeNode

object ViewHierarchySerializer {

    fun serialize(root: TreeNode, maxNodes: Int = 150): String {
        val lines = mutableListOf<String>()
        collectNodes(root, depth = 0, lines = lines, maxNodes = maxNodes)
        return lines.joinToString("\n")
    }

    private fun collectNodes(
        node: TreeNode,
        depth: Int,
        lines: MutableList<String>,
        maxNodes: Int,
    ) {
        if (lines.size >= maxNodes) return

        val text = node.attributes["text"]?.takeIf { it.isNotBlank() }
        val a11y = node.attributes["accessibilityText"]?.takeIf { it.isNotBlank() }
        val resourceId = node.attributes["resource-id"]?.takeIf { it.isNotBlank() }
        val bounds = node.attributes["bounds"]?.takeIf { it.isNotBlank() }
        val clickable = node.clickable == true

        val hasMeaningfulContent = text != null || a11y != null || resourceId != null || clickable

        if (hasMeaningfulContent) {
            val indent = "  ".repeat(depth)
            val parts = mutableListOf<String>()

            if (bounds != null) parts.add(bounds)
            if (text != null) parts.add("text=\"$text\"")
            if (a11y != null) parts.add("a11y=\"$a11y\"")
            if (resourceId != null) parts.add("rid=\"$resourceId\"")
            if (clickable) parts.add("[clickable]")

            lines.add("$indent${parts.joinToString(" ")}")
        }

        for (child in node.children) {
            if (lines.size >= maxNodes) break
            collectNodes(child, depth + if (hasMeaningfulContent) 1 else 0, lines, maxNodes)
        }
    }
}
