package xcuitest.api

data class SwipeRequest(
    val appId: String?,
    val startX: Double,
    val startY: Double,
    val endX: Double,
    val endY: Double,
    val duration: Double
)
