package maestro.conformance.logcat

data class FixtureEvent(
    val epoch: String,
    val seq: Int,
    val type: String,
    val payload: Map<String, Any?>,
)

data class Watermark(val epoch: String, val seq: Int)
