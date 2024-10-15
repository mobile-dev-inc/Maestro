import maestro.utils.Insight
import maestro.utils.Insights
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InsightsTest {

    @Test
    fun `report should update insight and notify listeners`() {
        val insight = Insight("Test message", Insight.Level.INFO)
        var notifiedInsight: Insight? = null

        Insights.onInsightsUpdated { notifiedInsight = it }
        Insights.report(insight)

        assertEquals(insight, notifiedInsight)
    }

    @Test
    fun `onInsightsUpdated should add a listener`() {
        val insight = Insight("Test message", Insight.Level.INFO)
        var notified = false

        Insights.onInsightsUpdated { notified = true }
        Insights.report(insight)

        assertTrue(notified)
    }

    @Test
    fun `unregisterListener should remove a listener`() {
        val insight = Insight("Test message", Insight.Level.INFO)
        var notified = false
        val listener: (Insight) -> Unit = { notified = true }

        Insights.onInsightsUpdated(listener)
        Insights.unregisterListener(listener)
        Insights.report(insight)

        assertTrue(!notified)
    }
}