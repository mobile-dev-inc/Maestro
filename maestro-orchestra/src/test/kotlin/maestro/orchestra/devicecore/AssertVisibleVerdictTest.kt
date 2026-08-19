package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class AssertVisibleVerdictTest {
    @Test
    fun `resolved and visible - VISIBLE mode passes`() {
        val ev = DeviceCoreEvidence.resolvedVisible("Form Test")
        assertThat(AssertVisibleVerdict.pass(ev, AssertMode.VISIBLE)).isTrue()
        assertThat(AssertVisibleVerdict.pass(ev, AssertMode.NOT_VISIBLE)).isFalse()
    }

    @Test
    fun `absent - NOT_VISIBLE mode passes`() {
        val ev = DeviceCoreEvidence.absent("kwyjibo")
        assertThat(AssertVisibleVerdict.pass(ev, AssertMode.NOT_VISIBLE)).isTrue()
        assertThat(AssertVisibleVerdict.pass(ev, AssertMode.VISIBLE)).isFalse()
    }

    @Test
    fun `unavailable visibility signal throws DeviceCoreUnavailable`() {
        val ev = DeviceCoreEvidence.resolvedUnavailableSignal("Form Test")
        assertThrows<DeviceCoreUnavailable> { AssertVisibleVerdict.pass(ev, AssertMode.VISIBLE) }
    }
}
