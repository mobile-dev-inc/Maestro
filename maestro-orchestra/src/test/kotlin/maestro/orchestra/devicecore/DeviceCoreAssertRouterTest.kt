package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.*
import kotlinx.coroutines.runBlocking
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import org.junit.jupiter.api.Test

class DeviceCoreAssertRouterTest {
    private val ua = Signal(false, EvidenceSource.UNAVAILABLE)
    private fun resolved(x: Int, y: Int, w: Int, h: Int) = ElementEvidence(
        "t", Resolution.Resolved(ResolvedChannel.TEXT),
        Actionability(ua, ua, Signal(true, EvidenceSource.MEASURED), ua, ua),
        Sourced(Rect(x, y, w, h), EvidenceSource.MEASURED),
    )
    private fun absent() = ElementEvidence(
        "t", Resolution.Absent(SearchedSurface.WHOLE_SCREEN),
        Actionability(ua, ua, ua, ua, ua), Sourced(null, EvidenceSource.UNAVAILABLE),
    )

    @Test fun `canRoute mirrors DeviceCoreRouting`() {
        val r = DeviceCoreAssertRouter("com.x") { FakeDeviceProvider { resolved(1,1,10,10) } }
        assertThat(r.canRoute(Condition(visible = ElementSelector(textRegex = "Hi")))).isTrue()
        assertThat(r.canRoute(Condition(visible = ElementSelector(idRegex = "hi")))).isFalse()
    }

    @Test fun `evaluate visible on a resolved on-screen element returns true, targets IOS_SIM by text`() {
        val fake = FakeDeviceProvider { resolved(122, 160, 148, 26) }
        val r = DeviceCoreAssertRouter("com.x") { fake }
        val pass = runBlocking { r.evaluate(Condition(visible = ElementSelector(textRegex = "Welcome")), 393, 852) }
        assertThat(pass).isTrue()
        assertThat(fake.lastConnectedTarget?.target).isEqualTo(TargetId.IOS_SIM)
        assertThat(fake.lastSelector).isEqualTo(Selector.Text("Welcome", Match.EXACT))
        assertThat(System.getProperty("devicecore.ios.bundleId")).isEqualTo("com.x")
    }

    @Test fun `evaluate visible on an absent element returns false (the negative control)`() {
        val r = DeviceCoreAssertRouter("com.x") { FakeDeviceProvider { absent() } }
        val pass = runBlocking { r.evaluate(Condition(visible = ElementSelector(textRegex = "Nope")), 393, 852) }
        assertThat(pass).isFalse()
    }

    @Test fun `evaluate applies nth for an indexed selector`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val r = DeviceCoreAssertRouter("com.x") { fake }
        runBlocking { r.evaluate(Condition(visible = ElementSelector(textRegex = "Row", index = "2")), 393, 852) }
        assertThat(fake.lastSelector).isEqualTo(Selector.Nth(Selector.Text("Row", Match.EXACT), 2))
    }
}
