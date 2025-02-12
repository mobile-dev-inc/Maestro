package maestro.utils

interface Insights {

    fun report(insight: Insight)

    fun onInsightsUpdated(callback: (Insight) -> Unit)

    fun unregisterListener(callback: (Insight) -> Unit)
}

object NoopInsights: Insights {

    override fun report(insight: Insight) {
        /* no-op */
    }

    override fun onInsightsUpdated(callback: (Insight) -> Unit) {
        /* no-op */
    }

    override fun unregisterListener(callback: (Insight) -> Unit) {
        /* no-op */
    }

}


data class Insight(
    val message: String,
    val level: Level
) {
    enum class Level {
        WARNING,
        INFO,
        NONE
    }
}