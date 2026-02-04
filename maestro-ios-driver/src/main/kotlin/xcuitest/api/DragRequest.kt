package xcuitest.api

data class DragRequest(
    val appId: String? = null,
    val startX: Double? = null,
    val startY: Double? = null,
    val endX: Double? = null,
    val endY: Double? = null,
    val duration: Double,
    val fromText: String? = null,
    val toText: String? = null,
    val toOffsetX: Double? = null,
    val toOffsetY: Double? = null,
)
