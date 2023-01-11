package api

data class SwipeRequest(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float,
    val velocity: Float? = null
)