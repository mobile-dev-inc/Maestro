package hierarchy

import com.fasterxml.jackson.annotation.JsonProperty

data class AXFrame(
    @JsonProperty("X") val x: Double,
    @JsonProperty("Y") val y: Double,
    @JsonProperty("Width") val width: Double,
    @JsonProperty("Height") val height: Double,
) {
    val left = x
    val right = x + width
    val top = y
    val bottom = y + height
    val boundsString = "[${left.toInt()},${top.toInt()}][${right.toInt()},${bottom.toInt()}]"
}

data class ViewHierarchy(
    val axElement: AXElement,
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
