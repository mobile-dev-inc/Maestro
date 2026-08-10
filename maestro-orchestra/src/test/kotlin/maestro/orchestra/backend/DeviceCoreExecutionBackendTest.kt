package maestro.orchestra.backend

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.ANDROID_EMU
import dev.mobile.devicecore.prototype.api.Actionability
import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.EvidenceSource
import dev.mobile.devicecore.prototype.api.Rect
import dev.mobile.devicecore.prototype.api.Resolution
import dev.mobile.devicecore.prototype.api.ResolvedChannel
import dev.mobile.devicecore.prototype.api.SearchedSurface
import dev.mobile.devicecore.prototype.api.Selector
import dev.mobile.devicecore.prototype.api.Signal
import dev.mobile.devicecore.prototype.api.Sourced
import dev.mobile.devicecore.prototype.api.TargetId
import kotlinx.coroutines.runBlocking
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.devicecore.FakeDeviceProvider
import maestro.SwipeDirection
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeviceCoreExecutionBackendTest {

    private val ctx = BackendContext(lookupTimeoutMs = 17000L, optionalLookupTimeoutMs = 7000L)

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
    private fun unavailable() = ElementEvidence(
        "t", Resolution.Unavailable,
        Actionability(ua, ua, ua, ua, ua), Sourced(null, EvidenceSource.UNAVAILABLE),
    )

    private fun backend(
        fake: FakeDeviceProvider,
        screen: Pair<Int, Int> = 393 to 852,
    ): DeviceCoreExecutionBackend {
        val b = DeviceCoreExecutionBackend(appId = "com.x", providerFactory = { fake }, screenSize = screen)
        b.open("com.x", null)
        return b
    }

    private fun assertVisible(text: String) =
        AssertConditionCommand(Condition(visible = ElementSelector(textRegex = text)))

    private fun assertNotVisible(text: String) =
        AssertConditionCommand(Condition(notVisible = ElementSelector(textRegex = text)))

    // --- assertVisible / assertNotVisible ---

    @Test fun `assertVisible on a resolved on-screen element is a PASS trace, targets ANDROID_EMU by text`() {
        val fake = FakeDeviceProvider { resolved(122, 160, 148, 26) }
        val b = backend(fake)
        val result = runBlocking { b.execute(assertVisible("Welcome"), ctx) }

        assertThat(result.mutating).isFalse()
        assertThat(result.trace?.verdict).isEqualTo(Verdict.PASS)
        assertThat(result.trace?.declined).isFalse()
        assertThat(result.trace?.chosenElement?.text).isEqualTo("t")
        assertThat(fake.lastConnectedTarget?.target).isEqualTo(TargetId.ANDROID_EMU)
        assertThat(fake.lastInspectedSelector).isEqualTo(Selector.Text("Welcome", dev.mobile.devicecore.prototype.api.Match.EXACT, false))
    }

    @Test fun `assertVisible on an absent element is a FAIL trace, not ERROR`() {
        val b = backend(FakeDeviceProvider { absent() })
        val result = runBlocking { b.execute(assertVisible("Nope"), ctx) }
        assertThat(result.trace?.verdict).isEqualTo(Verdict.FAIL)
        assertThat(result.trace?.declined).isFalse()
    }

    @Test fun `assertNotVisible on an absent element is a PASS trace`() {
        val b = backend(FakeDeviceProvider { absent() })
        val result = runBlocking { b.execute(assertNotVisible("Spinner"), ctx) }
        assertThat(result.trace?.verdict).isEqualTo(Verdict.PASS)
    }

    @Test fun `assertNotVisible on a resolved on-screen element is a FAIL trace`() {
        val b = backend(FakeDeviceProvider { resolved(122, 160, 148, 26) })
        val result = runBlocking { b.execute(assertNotVisible("Spinner"), ctx) }
        assertThat(result.trace?.verdict).isEqualTo(Verdict.FAIL)
    }

    @Test fun `Resolution Unavailable surfaces as an ERROR trace, never a FAIL`() {
        val b = backend(FakeDeviceProvider { unavailable() })
        val result = runBlocking { b.execute(assertVisible("Whatever"), ctx) }
        assertThat(result.trace?.verdict).isEqualTo(Verdict.ERROR)
        assertThat(result.mutating).isFalse()
    }

    @Test fun `an off-screen resolved element FAILs assertVisible when screen dimensions are known`() {
        val b = backend(FakeDeviceProvider { resolved(10, 900, 100, 40) }, screen = 393 to 852)
        val result = runBlocking { b.execute(assertVisible("Below the fold"), ctx) }
        assertThat(result.trace?.verdict).isEqualTo(Verdict.FAIL)
    }

    // --- tapOn(id) ---

    @Test fun `tapOn a literal-id selector taps and returns a PASS mutating trace`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        val result = runBlocking { b.execute(TapOnElementCommand(ElementSelector(idRegex = "login_btn")), ctx) }

        assertThat(fake.tapCount).isEqualTo(1)
        assertThat(fake.lastTappedSelector).isEqualTo(Selector.Id("login_btn"))
        assertThat(result.mutating).isTrue()
        assertThat(result.trace?.verdict).isEqualTo(Verdict.PASS)
        assertThat(result.trace?.declined).isFalse()
        assertThat(result.trace?.chosenElement?.resourceId).isEqualTo("login_btn")
    }

    // --- decline paths ---

    @Test fun `an unsupported command type is declined, never a crash`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        val result = runBlocking { b.execute(SwipeCommand(direction = SwipeDirection.UP), ctx) }

        assertThat(result.trace?.declined).isTrue()
        assertThat(result.trace?.declinedReason).contains("SwipeCommand")
        assertThat(result.mutating).isFalse()
        assertThat(fake.tapCount).isEqualTo(0)
    }

    @Test fun `a non-routable assert selector (regex) is declined`() {
        val b = backend(FakeDeviceProvider { resolved(1, 1, 10, 10) })
        val cmd = AssertConditionCommand(Condition(visible = ElementSelector(textRegex = "Item .*")))
        val result = runBlocking { b.execute(cmd, ctx) }
        assertThat(result.trace?.declined).isTrue()
    }

    @Test fun `a non-routable tap selector (relative) is declined`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        val sel = ElementSelector(idRegex = "btn", below = ElementSelector(textRegex = "Header"))
        val result = runBlocking { b.execute(TapOnElementCommand(sel), ctx) }
        assertThat(result.trace?.declined).isTrue()
        assertThat(fake.tapCount).isEqualTo(0)
    }

    // --- evaluateCondition ---

    @Test fun `evaluateCondition returns true for a routable visible on a resolved element`() {
        val b = backend(FakeDeviceProvider { resolved(122, 160, 148, 26) })
        val pass = runBlocking { b.evaluateCondition(Condition(visible = ElementSelector(textRegex = "Hi")), false, null, ctx) }
        assertThat(pass).isTrue()
    }

    @Test fun `evaluateCondition returns false for a routable visible on an absent element`() {
        val b = backend(FakeDeviceProvider { absent() })
        val pass = runBlocking { b.evaluateCondition(Condition(visible = ElementSelector(textRegex = "Nope")), false, null, ctx) }
        assertThat(pass).isFalse()
    }

    @Test fun `evaluateCondition returns a safe true for a non-routable condition`() {
        val b = backend(FakeDeviceProvider { absent() })
        val pass = runBlocking { b.evaluateCondition(Condition(scriptCondition = "x"), false, null, ctx) }
        assertThat(pass).isTrue()
    }

    // --- lifecycle ---

    @Test fun `open connects exactly once and close closes the device`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = DeviceCoreExecutionBackend(appId = "com.x", providerFactory = { fake })
        b.open("com.x", null)
        runBlocking { b.execute(assertVisible("A"), ctx) }
        runBlocking { b.execute(assertVisible("B"), ctx) }
        assertThat(fake.connectCount).isEqualTo(1)

        b.close()
        assertThat(fake.closed).isTrue()
    }

    @Test fun `close is null-safe when open was never called`() {
        val b = DeviceCoreExecutionBackend(appId = "com.x", providerFactory = { FakeDeviceProvider { absent() } })
        b.close() // must not throw
    }

    // --- capture verbs decline via a typed exception ---

    @Test fun `takeScreenshot throws BackendUnsupportedOperation`() {
        val b = backend(FakeDeviceProvider { absent() })
        assertThrows<BackendUnsupportedOperation> {
            runBlocking { b.takeScreenshot(Buffer(), compressed = false) }
        }
    }

    @Test fun `startScreenRecording throws BackendUnsupportedOperation`() {
        val b = backend(FakeDeviceProvider { absent() })
        assertThrows<BackendUnsupportedOperation> {
            runBlocking { b.startScreenRecording(Buffer()) }
        }
    }

    @Test fun `hierarchySnapshot is null`() {
        val b = backend(FakeDeviceProvider { absent() })
        assertThat(b.hierarchySnapshot()).isNull()
    }
}
