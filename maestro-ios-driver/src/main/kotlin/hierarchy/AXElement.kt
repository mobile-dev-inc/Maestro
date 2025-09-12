package hierarchy

import com.fasterxml.jackson.annotation.JsonProperty

data class AXFrame(
    @JsonProperty("X") val x: Float,
    @JsonProperty("Y") val y: Float,
    @JsonProperty("Width") val width: Float,
    @JsonProperty("Height") val height: Float,
) {
    val left = x
    val right = x + width
    val top = y
    val bottom = y + height
    val boundsString = "[${left.toInt()},${top.toInt()}][${right.toInt()},${bottom.toInt()}]"
}

data class ViewHierarchy(
    val axElement: AXElement,
    val visualElement: AXElement,
    val depth: Int
)

data class AXElement(
    val label: String,
    val elementType: Int,
    val identifier: String,
    val horizontalSizeClass: Int,
    val windowContextID: Long,
    val verticalSizeClass: Int,
    val selected: Boolean,
    val displayID: Int,
    val hasFocus: Boolean,
    val placeholderValue: String?,
    val value: String?,
    val frame: AXFrame,
    val enabled: Boolean,
    val title: String?,
    val children: ArrayList<AXElement>,
)
