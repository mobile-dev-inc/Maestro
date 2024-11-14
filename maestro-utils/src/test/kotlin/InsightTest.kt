import maestro.utils.Insight
import maestro.utils.CliInsights
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliInsightsTest {

    @Test
    fun `report should update insight and notify listeners`() {
        val insight = Insight("Test message", Insight.Level.INFO)
        var notifiedInsight: Insight? = null

        CliInsights.onInsightsUpdated { notifiedInsight = it }
        CliInsights.report(insight)

        assertEquals(insight, notifiedInsight)
    }

    @Test
    fun `onInsightsUpdated should add a listener`() {
        val insight = Insight("Test message", Insight.Level.INFO)
        var notified = false

        CliInsights.onInsightsUpdated { notified = true }
        CliInsights.report(insight)

        assertTrue(notified)
    }

    @Test
    fun `unregisterListener should remove a listener`() {
        val insight = Insight("Test message", Insight.Level.INFO)
        var notified = false
        val listener: (Insight) -> Unit = { notified = true }

        CliInsights.onInsightsUpdated(listener)
        CliInsights.unregisterListener(listener)
        CliInsights.report(insight)

        assertTrue(!notified)
    }
}