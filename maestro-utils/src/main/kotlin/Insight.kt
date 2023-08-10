package maestro.utils

object Insights {

    private var insight: Insight = Insight("", Insight.Level.NONE)
    private val listeners = mutableListOf<(Insight) -> Unit>()

    fun report(insight: Insight) {
        this.insight = insight
        listeners.forEach { it.invoke(insight) }
    }

    fun onInsightsUpdated(callback: (Insight) -> Unit) {
        listeners.add(callback)
    }

    fun unregisterListener(callback: (Insight) -> Unit) {
        listeners.remove(callback)
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
