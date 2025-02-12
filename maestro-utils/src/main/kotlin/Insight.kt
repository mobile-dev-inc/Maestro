package maestro.utils

object CliInsights: Insights {

    private var insight: Insight = Insight("", Insight.Level.NONE)
    private val listeners = mutableListOf<(Insight) -> Unit>()

    override fun report(insight: Insight) {
        CliInsights.insight = insight
        listeners.forEach { it.invoke(insight) }
    }

    override fun onInsightsUpdated(callback: (Insight) -> Unit) {
        listeners.add(callback)
    }

    override fun unregisterListener(callback: (Insight) -> Unit) {
        listeners.remove(callback)
    }
}
