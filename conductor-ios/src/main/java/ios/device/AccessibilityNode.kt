package ios.device

import com.google.gson.annotations.SerializedName

data class AccessibilityNode(
    val frame: Frame?,
    val title: String?,
    val type: String?,
    @SerializedName("AXUniqueId") val axUniqueId: String?,
    @SerializedName("AXLabel") val axLabel: String?
) {

    data class Frame(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )
}
