package ios.hierarchy

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

@JsonDeserialize(`as` = AccessibilityNode::class)
data class AccessibilityNode(
    @JsonProperty("AXFrame") val axFrame: String,
    @JsonProperty("AXUniqueId") val axUniqueId: String?,
    @JsonProperty("role_description") val roleDescription: String?,
    @JsonProperty("AXLabel") val axLabel: String?,
    @JsonProperty("content_required") val contentRequired: Boolean?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("title") val title: String?,
    @JsonProperty("custom_actions") val customAction: ArrayList<String>,
    @JsonProperty("AXValue") val axValue: String?,
    @JsonProperty("role") val role: String?,
    @JsonProperty("help") val help: String?,
    @JsonProperty("subrole") val subRole: String?,
    @JsonProperty("enabled") val enabled: Boolean,
    @JsonProperty("frame") val frame: Frame
) {
    data class Frame(
        val x: Float,
        val y: Float,
        val width: Float,
        val height: Float
    )
}