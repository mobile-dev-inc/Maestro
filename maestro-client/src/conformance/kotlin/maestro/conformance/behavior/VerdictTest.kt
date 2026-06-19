package maestro.conformance.behavior

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class VerdictTest {
    @Test fun `pass verdict has no reason`() {
        val v = Verdict.pass()
        assertThat(v.pass).isTrue()
        assertThat(v.reason).isNull()
    }
    @Test fun `fail verdict carries reason`() {
        val v = Verdict.fail("dir was DOWN, expected UP")
        assertThat(v.pass).isFalse()
        assertThat(v.reason).contains("expected UP")
    }
    @Test fun `outcome records oracle kind and fields`() {
        val o = CommandOutcome(
            verdict = Verdict.pass(),
            oracleKind = OracleKind.APP_EVENT,
            expected = mapOf("event" to "TAP"),
            actual = mapOf("event" to "TAP"),
            args = mapOf("point" to listOf(10, 20)),
        )
        assertThat(o.oracleKind).isEqualTo(OracleKind.APP_EVENT)
        assertThat(o.actual["event"]).isEqualTo("TAP")
    }
}
