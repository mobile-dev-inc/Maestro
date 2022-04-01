package conductor

data class TreeNode(
    val attributes: Map<String, String> = emptyMap(),
    val children: List<TreeNode> = emptyList(),
    val clickable: Boolean = false,
)
