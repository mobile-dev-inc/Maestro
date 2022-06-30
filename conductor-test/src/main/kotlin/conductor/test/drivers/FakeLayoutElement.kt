package conductor.test.drivers

import conductor.TreeNode

data class FakeLayoutElement(
    var id: String? = null,
    var text: String? = null,
    var bounds: Bounds? = null,
    var clickable: Boolean = false,
    val children: MutableList<FakeLayoutElement> = mutableListOf(),
) {

    fun toTreeNode(): TreeNode {
        val attributes = mutableMapOf<String, String>()

        bounds?.let {
            attributes += "bounds" to "${it.left},${it.top},${it.right},${it.bottom}"
        }

        text?.let {
            attributes += "text" to it
        }

        id?.let {
            attributes += "resource-id" to it
        }

        return TreeNode(
            attributes = attributes,
            clickable = clickable,
            children = children.map { it.toTreeNode() }
        )
    }

    fun element(builder: FakeLayoutElement.() -> Unit): FakeLayoutElement {
        val child = FakeLayoutElement()
        child.builder()
        children.add(child)
        return child
    }

    data class Bounds(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

}