package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.AbsentVia
import dev.mobile.devicecore.prototype.api.ActionEvidence
import dev.mobile.devicecore.prototype.api.Actionability
import dev.mobile.devicecore.prototype.api.EvidenceSource
import dev.mobile.devicecore.prototype.api.FoundVia
import dev.mobile.devicecore.prototype.api.Outcome
import dev.mobile.devicecore.prototype.api.Point
import dev.mobile.devicecore.prototype.api.Settle
import dev.mobile.devicecore.prototype.api.Signal
import maestro.MaestroException
import org.junit.jupiter.api.Test

class WaitOutcomeVerdictTest {
    private val ua = Signal(false, EvidenceSource.UNAVAILABLE)
    private fun evidence(outcome: Outcome) = ActionEvidence(
        actionId = "a", target = "Form Test", outcome = outcome,
        actionability = Actionability(ua, ua, ua, ua, ua),
        delivered = Signal(true, EvidenceSource.MEASURED),
        settle = Settle(ua, ua), injectPoint = Point(1, 2), waitedMs = 0L,
    )

    @Test
    fun `Acted passes`() {
        assertThat(WaitOutcomeVerdict.toException(evidence(Outcome.Acted(FoundVia.IMMEDIATE)), "Form Test", 1000L)).isNull()
    }

    @Test
    fun `Absent fails with AssertionFailure`() {
        val ex = WaitOutcomeVerdict.toException(
            evidence(Outcome.Absent(AbsentVia.CAP_WHILE_QUIET, capMs = 500L)), "Form Test", 1000L
        )
        assertThat(ex).isInstanceOf(MaestroException.AssertionFailure::class.java)
    }

    @Test
    fun `Blocked fails with AssertionFailure`() {
        val ex = WaitOutcomeVerdict.toException(evidence(Outcome.Blocked("not hittable")), "Form Test", 1000L)
        assertThat(ex).isInstanceOf(MaestroException.AssertionFailure::class.java)
    }

    @Test
    fun `Crashed fails with AppCrash`() {
        val ex = WaitOutcomeVerdict.toException(evidence(Outcome.Crashed("com.example.example")), "Form Test", 1000L)
        assertThat(ex).isInstanceOf(MaestroException.AppCrash::class.java)
    }
}
