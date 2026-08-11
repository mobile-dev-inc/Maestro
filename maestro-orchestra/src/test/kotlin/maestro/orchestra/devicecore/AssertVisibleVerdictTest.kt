package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.*
import maestro.orchestra.backend.BackendUnsupportedOperation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AssertVisibleVerdictTest {
    private val ua = Signal(false, EvidenceSource.UNAVAILABLE)
    private val bounds = Sourced(Rect(10, 10, 40, 20), EvidenceSource.MEASURED)

    private fun resolved(visible: Signal) =
        ElementEvidence("t", Resolution.Resolved(ResolvedChannel.TEXT), Actionability(ua, visible, ua, ua, ua), bounds)

    @Test fun `resolved with a measured visible-true signal is visible`() {
        val e = resolved(Signal(true, EvidenceSource.MEASURED))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE)).isTrue()
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.NOT_VISIBLE)).isFalse()
    }

    @Test fun `resolved with a measured visible-false signal is not visible`() {
        val e = resolved(Signal(false, EvidenceSource.MEASURED))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE)).isFalse()
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.NOT_VISIBLE)).isTrue()
    }

    @Test fun `an inferred visible signal is a real answer and is honored`() {
        val e = resolved(Signal(true, EvidenceSource.INFERRED))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE)).isTrue()
    }

    @Test fun `resolved with NO visibility signal (UNAVAILABLE) throws BackendUnsupportedOperation — an owed capability, never a silent verdict`() {
        val e = resolved(ua) // visible.source == UNAVAILABLE
        assertThrows<BackendUnsupportedOperation> { AssertVisibleVerdict.pass(e, AssertMode.VISIBLE) }
        assertThrows<BackendUnsupportedOperation> { AssertVisibleVerdict.pass(e, AssertMode.NOT_VISIBLE) }
    }

    @Test fun `absent is not visible, and passes notVisible`() {
        val e = ElementEvidence("t", Resolution.Absent(SearchedSurface.WHOLE_SCREEN),
            Actionability(ua, ua, ua, ua, ua), Sourced(null, EvidenceSource.UNAVAILABLE))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE)).isFalse()
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.NOT_VISIBLE)).isTrue()
    }

    @Test fun `ambiguous throws BackendUnsupportedOperation for both modes — no single-element verdict`() {
        val e = ElementEvidence("t", Resolution.Ambiguous(3),
            Actionability(ua, ua, ua, ua, ua), Sourced(null, EvidenceSource.UNAVAILABLE))
        assertThrows<BackendUnsupportedOperation> { AssertVisibleVerdict.pass(e, AssertMode.VISIBLE) }
        assertThrows<BackendUnsupportedOperation> { AssertVisibleVerdict.pass(e, AssertMode.NOT_VISIBLE) }
    }

    @Test fun `unavailable throws DeviceCoreUnavailable for both modes (infra), never a silent verdict`() {
        val e = ElementEvidence("t", Resolution.Unavailable,
            Actionability(ua, ua, ua, ua, ua), Sourced(null, EvidenceSource.UNAVAILABLE))
        assertThrows<DeviceCoreUnavailable> { AssertVisibleVerdict.pass(e, AssertMode.VISIBLE) }
        assertThrows<DeviceCoreUnavailable> { AssertVisibleVerdict.pass(e, AssertMode.NOT_VISIBLE) }
    }
}
