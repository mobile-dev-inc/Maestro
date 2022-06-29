package conductor

data class UiElement(
    val bounds: Bounds,
) {

    companion object {

        fun TreeNode.toUiElement(): UiElement {
            // TODO needs different impl for iOS
            val boundsStr = attributes["bounds"]
                ?: throw IllegalStateException("Node has no bounds")

            val boundsArr = boundsStr
                .replace("][", ",")
                .removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .map { it.toInt() }

            return UiElement(
                Bounds(
                    x = boundsArr[0],
                    y = boundsArr[1],
                    width = boundsArr[2] - boundsArr[0],
                    height = boundsArr[3] - boundsArr[1]
                )
            )
        }
    }
}
