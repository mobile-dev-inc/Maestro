package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.*
import kotlinx.coroutines.runBlocking
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeviceCoreAssertRouterTest {
    @AfterEach
    fun clearGlobalState() {
        System.clearProperty("devicecore.ios.bundleId")
    }

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
        // Set-before-connect ordering, not just final presence: the property must already be
        // "com.x" at the moment connect() runs.
        assertThat(fake.bundleIdAtConnect).isEqualTo("com.x")
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

    @Test fun `evaluate wraps a plain infra exception from inspect() as DeviceCoreUnavailable`() {
        val fake = FakeDeviceProvider { throw RuntimeException("socket refused") }
        val r = DeviceCoreAssertRouter("com.x") { fake }
        val thrown = assertThrows<DeviceCoreUnavailable> {
            runBlocking { r.evaluate(Condition(visible = ElementSelector(textRegex = "Hi")), 393, 852) }
        }
        assertThat(thrown.message).contains("socket refused")
    }

    @Test fun `evaluate propagates a DeviceCoreUnavailable from inspect() without double-wrapping`() {
        val original = DeviceCoreUnavailable("driver down")
        val fake = FakeDeviceProvider { throw original }
        val r = DeviceCoreAssertRouter("com.x") { fake }
        val thrown = assertThrows<DeviceCoreUnavailable> {
            runBlocking { r.evaluate(Condition(visible = ElementSelector(textRegex = "Hi")), 393, 852) }
        }
        assertThat(thrown).isSameInstanceAs(original)
        assertThat(thrown.message).isEqualTo("driver down")
    }

    @Test fun `evaluate throws IllegalArgumentException for a non-routable condition`() {
        val r = DeviceCoreAssertRouter("com.x") { FakeDeviceProvider { resolved(1, 1, 10, 10) } }
        assertThrows<IllegalArgumentException> {
            runBlocking { r.evaluate(Condition(visible = ElementSelector(idRegex = "x")), 393, 852) }
        }
    }

    @Test fun `evaluate notVisible on an absent element returns true`() {
        val r = DeviceCoreAssertRouter("com.x") { FakeDeviceProvider { absent() } }
        val pass = runBlocking {
            r.evaluate(Condition(notVisible = ElementSelector(textRegex = "Spinner")), 393, 852)
        }
        assertThat(pass).isTrue()
    }

    @Test fun `evaluate notVisible on a resolved on-screen element returns false`() {
        val r = DeviceCoreAssertRouter("com.x") { FakeDeviceProvider { resolved(122, 160, 148, 26) } }
        val pass = runBlocking {
            r.evaluate(Condition(notVisible = ElementSelector(textRegex = "Spinner")), 393, 852)
        }
        assertThat(pass).isFalse()
    }
}
