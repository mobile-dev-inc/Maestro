package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AssertVisibleVerdictTest {
    private val ua = Signal(false, EvidenceSource.UNAVAILABLE)
    private fun evidence(res: Resolution, bounds: Sourced<Rect>) =
        ElementEvidence("t", res, Actionability(ua, ua, ua, ua, ua), bounds)
    private fun measured(x: Int, y: Int, w: Int, h: Int) =
        Sourced(Rect(x, y, w, h), EvidenceSource.MEASURED)

    private val W = 393
    private val H = 852

    @Test fun `resolved with on-screen measured bounds is visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), measured(122, 160, 148, 26))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isTrue()
    }

    @Test fun `absent is not visible`() {
        val e = evidence(Resolution.Absent(SearchedSurface.WHOLE_SCREEN), Sourced(null, EvidenceSource.UNAVAILABLE))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `ambiguous is not visible`() {
        val e = evidence(Resolution.Ambiguous(3), Sourced(null, EvidenceSource.UNAVAILABLE))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `resolved but off-screen (below viewport) is not visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), measured(10, 900, 100, 40))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `resolved but off-screen (right edge overflow) is not visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), measured(350, 100, 100, 20))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `resolved but off-screen (negative x) is not visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), measured(-5, 100, 40, 20))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `resolved but off-screen (negative y) is not visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), measured(10, -5, 40, 20))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `resolved but zero-area is not visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), measured(10, 10, 0, 0))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `resolved but zero width is not visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), measured(10, 10, 0, 40))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `resolved but zero height is not visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), measured(10, 10, 40, 0))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `resolved but bounds only INFERRED is not visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), Sourced(Rect(1,1,10,10), EvidenceSource.INFERRED))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H)).isFalse()
    }

    @Test fun `notVisible passes when element is absent`() {
        val e = evidence(Resolution.Absent(SearchedSurface.WHOLE_SCREEN), Sourced(null, EvidenceSource.UNAVAILABLE))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.NOT_VISIBLE, W, H)).isTrue()
    }

    @Test fun `notVisible fails when element is visible`() {
        val e = evidence(Resolution.Resolved(ResolvedChannel.TEXT), measured(122, 160, 148, 26))
        assertThat(AssertVisibleVerdict.pass(e, AssertMode.NOT_VISIBLE, W, H)).isFalse()
    }

    @Test fun `unavailable throws for both modes, never a silent verdict`() {
        val e = evidence(Resolution.Unavailable, Sourced(null, EvidenceSource.UNAVAILABLE))
        assertThrows<DeviceCoreUnavailable> { AssertVisibleVerdict.pass(e, AssertMode.VISIBLE, W, H) }
        assertThrows<DeviceCoreUnavailable> { AssertVisibleVerdict.pass(e, AssertMode.NOT_VISIBLE, W, H) }
    }
}
