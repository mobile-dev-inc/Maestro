package maestro.orchestra.backend

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.ANDROID_EMU
import dev.mobile.devicecore.prototype.api.Actionability
import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.EvidenceSource
import dev.mobile.devicecore.prototype.api.IOS_SIM
import dev.mobile.devicecore.prototype.api.MatchedText
import dev.mobile.devicecore.prototype.api.Rect
import dev.mobile.devicecore.prototype.api.Resolution
import dev.mobile.devicecore.prototype.api.ResolvedChannel
import dev.mobile.devicecore.prototype.api.SearchedSurface
import dev.mobile.devicecore.prototype.api.Selector
import dev.mobile.devicecore.prototype.api.Signal
import dev.mobile.devicecore.prototype.api.Sourced
import dev.mobile.devicecore.prototype.api.TargetId
import dev.mobile.devicecore.prototype.api.TextChannel
import kotlinx.coroutines.runBlocking
import maestro.MaestroException
import maestro.device.Platform
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.devicecore.DeviceCoreUnavailable
import maestro.orchestra.devicecore.FakeDeviceProvider
import maestro.SwipeDirection
import maestro.TapRepeat
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class DeviceCoreExecutionBackendTest {

    private val ctx = BackendContext(lookupTimeoutMs = 17000L, optionalLookupTimeoutMs = 7000L)

    private val ua = Signal(false, EvidenceSource.UNAVAILABLE)
    // [matchedText] defaults to null (matched.value UNAVAILABLE) so the many callers that don't care
    // about chosenElement.text are unaffected; tests that DO care (Fix for finding #5) pass it
    // explicitly and deliberately distinct from the hardcoded "t" target, to prove chosenElement.text
    // comes from the matched-text evidence, never the query descriptor.
    private fun resolved(x: Int, y: Int, w: Int, h: Int, matchedText: String? = null) = ElementEvidence(
        "t", Resolution.Resolved(ResolvedChannel.TEXT),
        Actionability(ua, Signal(true, EvidenceSource.MEASURED), ua, ua, ua),
        Sourced(Rect(x, y, w, h), EvidenceSource.MEASURED),
        matched = matchedText?.let { Sourced(MatchedText(it, TextChannel.LABEL), EvidenceSource.MEASURED) }
            ?: Sourced(null, EvidenceSource.UNAVAILABLE),
    )
    private fun resolvedNotVisible() = ElementEvidence(
        "t", Resolution.Resolved(ResolvedChannel.TEXT),
        Actionability(ua, Signal(false, EvidenceSource.MEASURED), ua, ua, ua),
        Sourced(Rect(1, 1, 10, 10), EvidenceSource.MEASURED),
    )
    private fun absent() = ElementEvidence(
        "t", Resolution.Absent(SearchedSurface.WHOLE_SCREEN),
        Actionability(ua, ua, ua, ua, ua), Sourced(null, EvidenceSource.UNAVAILABLE),
    )
    private fun unavailable() = ElementEvidence(
        "t", Resolution.Unavailable,
        Actionability(ua, ua, ua, ua, ua), Sourced(null, EvidenceSource.UNAVAILABLE),
    )

    private fun backend(fake: FakeDeviceProvider): DeviceCoreExecutionBackend {
        val b = DeviceCoreExecutionBackend(platform = Platform.ANDROID, appId = "com.x", providerFactory = { fake })
        b.open("com.x", null)
        return b
    }

    private fun assertVisible(text: String) =
        AssertConditionCommand(Condition(visible = ElementSelector(textRegex = text)))

    private fun assertNotVisible(text: String) =
        AssertConditionCommand(Condition(notVisible = ElementSelector(textRegex = text)))

    // --- assertVisible / assertNotVisible ---

    @Test fun `assertVisible on a resolved on-screen element is a PASS trace, targets ANDROID_EMU by text`() {
        val fake = FakeDeviceProvider { resolved(122, 160, 148, 26, matchedText = "Welcome back") }
        val b = backend(fake)
        val result = runBlocking { b.execute(assertVisible("Welcome"), ctx) }

        assertThat(result.mutating).isFalse()
        // The evidence's matched text ("Welcome back"), never the "t" target/query-descriptor field —
        // and never the query text ("Welcome") either. Regression coverage for finding #5.
        assertThat(result.trace?.chosenElement?.text).isEqualTo("Welcome back")
        assertThat(fake.lastConnectedTarget?.target).isEqualTo(TargetId.ANDROID_EMU)
        assertThat(fake.lastInspectedSelector).isEqualTo(Selector.Text("Welcome", dev.mobile.devicecore.prototype.api.Match.EXACT, false))
    }

    @Test fun `chosenElement text is the resolved element's matched text, never the query descriptor (finding #5)`() {
        // Mirrors the real-run repro from fidelity-run-report.md finding #5: device-core's own
        // `target` field carries the query descriptor string, distinctly NOT the rendered element
        // text. chosenElement.text must come from `matched`, never `target`.
        val evidence = ElementEvidence(
            target = "text=Notifications(EXACT)",
            resolution = Resolution.Resolved(ResolvedChannel.TEXT),
            actionability = Actionability(ua, Signal(true, EvidenceSource.MEASURED), ua, ua, ua),
            bounds = Sourced(Rect(1, 2, 3, 4), EvidenceSource.MEASURED),
            matched = Sourced(MatchedText("Notifications", TextChannel.LABEL), EvidenceSource.MEASURED),
        )
        val b = backend(FakeDeviceProvider { evidence })
        val result = runBlocking { b.execute(assertVisible("Notifications"), ctx) }

        assertThat(result.trace?.chosenElement?.text).isEqualTo("Notifications")
        // device-core surfaces no resource-id today — left null, never fabricated.
        assertThat(result.trace?.chosenElement?.resourceId).isNull()
    }

    @Test fun `chosenElement text is null, not the query descriptor, when device-core reports no matched-text evidence`() {
        val b = backend(FakeDeviceProvider { resolved(1, 1, 10, 10) }) // matchedText left default (UNAVAILABLE)
        val result = runBlocking { b.execute(assertVisible("Whatever"), ctx) }

        assertThat(result.trace?.chosenElement?.text).isNull()
    }

    @Test fun `assertVisible on an absent element THROWS AssertionFailure (lifecycle FAIL), not ERROR`() {
        // A failed assert must throw a MaestroException so Orchestra's lifecycle derives FAIL. Returning
        // a FAIL trace would be read as PASS and the failed assert would silently pass.
        val b = backend(FakeDeviceProvider { absent() })
        assertThrows<MaestroException.AssertionFailure> {
            runBlocking { b.execute(assertVisible("Nope"), ctx) }
        }
    }

    @Test fun `assertNotVisible on an absent element is a PASS trace`() {
        val b = backend(FakeDeviceProvider { absent() })
        val result = runBlocking { b.execute(assertNotVisible("Spinner"), ctx) }
        assertThat(result.mutating).isFalse()
    }

    @Test fun `assertNotVisible on a resolved on-screen element THROWS AssertionFailure`() {
        val b = backend(FakeDeviceProvider { resolved(122, 160, 148, 26) })
        assertThrows<MaestroException.AssertionFailure> {
            runBlocking { b.execute(assertNotVisible("Spinner"), ctx) }
        }
    }

    @Test fun `Resolution Unavailable propagates DeviceCoreUnavailable (lifecycle ERROR), never a FAIL`() {
        // DeviceCoreUnavailable is not a MaestroException, so Orchestra maps it to ERROR — the router's
        // cue to re-run on legacy, not to fail the flow. The backend must not swallow it into a trace.
        val b = backend(FakeDeviceProvider { unavailable() })
        assertThrows<DeviceCoreUnavailable> {
            runBlocking { b.execute(assertVisible("Whatever"), ctx) }
        }
    }

    @Test fun `a resolved-but-not-visible element THROWS AssertionFailure for assertVisible`() {
        val b = backend(FakeDeviceProvider { resolvedNotVisible() })
        assertThrows<MaestroException.AssertionFailure> {
            runBlocking { b.execute(assertVisible("Hidden"), ctx) }
        }
    }

    @Test fun `a resolved element with no visibility signal propagates BackendUnsupportedOperation (owed capability, lifecycle ERROR gap)`() {
        val noVisSignal = ElementEvidence(
            "t", Resolution.Resolved(ResolvedChannel.TEXT),
            Actionability(ua, ua, ua, ua, ua),
            Sourced(Rect(1, 1, 10, 10), EvidenceSource.MEASURED),
        )
        val b = backend(FakeDeviceProvider { noVisSignal })
        assertThrows<BackendUnsupportedOperation> {
            runBlocking { b.execute(assertVisible("Whatever"), ctx) }
        }
    }

    // --- tapOn(id) ---

    @Test fun `tapOn a literal-id selector taps and returns a PASS mutating trace`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        val result = runBlocking { b.execute(TapOnElementCommand(ElementSelector(idRegex = "login_btn")), ctx) }

        assertThat(fake.tapCount).isEqualTo(1)
        assertThat(fake.lastTappedSelector).isEqualTo(Selector.Id("login_btn"))
        assertThat(result.mutating).isTrue()
        assertThat(result.trace?.chosenElement?.resourceId).isEqualTo("login_btn")
    }

    @Test fun `tapOn a literal-id selector whose tap fails THROWS ElementNotFound (lifecycle FAIL)`() {
        // A failed .tap() must throw a MaestroException (matching legacy's ElementNotFound) so the
        // lifecycle derives FAIL. Returning a trace would be read as PASS and the failed tap would pass.
        val fake = FakeDeviceProvider(
            evidenceFor = { resolved(1, 1, 10, 10) },
            onTap = { throw RuntimeException("no such element") },
        )
        val b = backend(fake)
        assertThrows<MaestroException.ElementNotFound> {
            runBlocking { b.execute(TapOnElementCommand(ElementSelector(idRegex = "login_btn")), ctx) }
        }
    }

    @Test fun `tapOn where the driver is unavailable propagates DeviceCoreUnavailable (lifecycle ERROR)`() {
        val fake = FakeDeviceProvider(
            evidenceFor = { resolved(1, 1, 10, 10) },
            onTap = { throw DeviceCoreUnavailable("socket refused") },
        )
        val b = backend(fake)
        assertThrows<DeviceCoreUnavailable> {
            runBlocking { b.execute(TapOnElementCommand(ElementSelector(idRegex = "login_btn")), ctx) }
        }
    }

    @Test fun `tapOn(id) with longPress throws BackendUnsupportedOperation, never a silent plain tap`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        val cmd = TapOnElementCommand(ElementSelector(idRegex = "login_btn"), longPress = true)
        assertThrows<BackendUnsupportedOperation> { runBlocking { b.execute(cmd, ctx) } }
        assertThat(fake.tapCount).isEqualTo(0)
    }

    @Test fun `tapOn(id) with repeat throws BackendUnsupportedOperation, never a silent single tap`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        val cmd = TapOnElementCommand(
            ElementSelector(idRegex = "login_btn"),
            repeat = TapRepeat(2, TapOnElementCommand.DEFAULT_REPEAT_DELAY),
        )
        assertThrows<BackendUnsupportedOperation> { runBlocking { b.execute(cmd, ctx) } }
        assertThat(fake.tapCount).isEqualTo(0)
    }

    @Test fun `tapOn(id) with a relativePoint throws BackendUnsupportedOperation, never a silent centered tap`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        val cmd = TapOnElementCommand(ElementSelector(idRegex = "login_btn"), relativePoint = "50%,50%")
        assertThrows<BackendUnsupportedOperation> { runBlocking { b.execute(cmd, ctx) } }
        assertThat(fake.tapCount).isEqualTo(0)
    }

    // --- launchApp ---

    @Test fun `a plain launch routes to device launchApp and is a mutating trace`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        val result = runBlocking { b.execute(LaunchAppCommand(appId = "com.x"), ctx) }

        assertThat(result.mutating).isTrue()
        assertThat(result.trace?.chosenElement).isNull()
        assertThat(fake.launchedApps).containsExactly("com.x")
    }

    @Test fun `a launch with clearState throws BackendUnsupportedOperation, never a silent bare launch`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        assertThrows<BackendUnsupportedOperation> {
            runBlocking { b.execute(LaunchAppCommand(appId = "com.x", clearState = true), ctx) }
        }
        assertThat(fake.launchedApps).isEmpty()
    }

    @Test fun `a launch with permissions throws BackendUnsupportedOperation, never a silent bare launch`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        val cmd = LaunchAppCommand(appId = "com.x", permissions = mapOf("all" to "allow"))
        assertThrows<BackendUnsupportedOperation> { runBlocking { b.execute(cmd, ctx) } }
        assertThat(fake.launchedApps).isEmpty()
    }

    @Test fun `a launch with stopApp=false throws BackendUnsupportedOperation, never a silent stop-then-launch`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        assertThrows<BackendUnsupportedOperation> {
            runBlocking { b.execute(LaunchAppCommand(appId = "com.x", stopApp = false), ctx) }
        }
        assertThat(fake.launchedApps).isEmpty()
    }

    @Test fun `a launch with stopApp=true routes to device launchApp (the default path is served)`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        val result = runBlocking { b.execute(LaunchAppCommand(appId = "com.x", stopApp = true), ctx) }

        assertThat(result.mutating).isTrue()
        assertThat(fake.launchedApps).containsExactly("com.x")
    }

    @Test fun `a failing launch propagates device-core's own InjectionUnavailable unchanged (lifecycle ERROR, not FAIL)`() {
        // InjectionUnavailable is device-core's own typed "target could not be brought up" throw — it
        // must NOT be swallowed/rewrapped, only genuinely unmapped throwables are (see next test).
        val fake = FakeDeviceProvider(launchFails = true) { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        val thrown = assertThrows<dev.mobile.devicecore.prototype.api.InjectionUnavailable> {
            runBlocking { b.execute(LaunchAppCommand(appId = "com.x"), ctx) }
        }
        assertThat(thrown).isNotInstanceOf(MaestroException::class.java)
    }

    @Test fun `a launch failing with an exception device-core doesn't map is wrapped as DeviceCoreUnavailable, never a raw escape (finding #4)`() {
        // e.g. resolveSerial()'s IllegalStateException("expected exactly one adb device...") when the
        // TargetSelector carries no serial and >1 device is attached — genuinely unmapped by
        // device-core, must still land as a typed, ERROR-shaped exception, not an uncaught crash.
        val fake = FakeDeviceProvider(launchThrows = IllegalStateException("expected exactly one adb device, found 2")) {
            resolved(1, 1, 10, 10)
        }
        val b = backend(fake)
        val thrown = assertThrows<DeviceCoreUnavailable> {
            runBlocking { b.execute(LaunchAppCommand(appId = "com.x"), ctx) }
        }
        assertThat(thrown).isNotInstanceOf(MaestroException::class.java)
        assertThat(thrown.message).contains("expected exactly one adb device")
    }

    // --- hard-fail (gap) paths ---

    @Test fun `an unhandled command type throws BackendUnsupportedOperation, never a crash`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        val thrown = assertThrows<BackendUnsupportedOperation> {
            runBlocking { b.execute(SwipeCommand(direction = SwipeDirection.UP), ctx) }
        }
        assertThat(thrown.message).contains("SwipeCommand")
        assertThat(fake.tapCount).isEqualTo(0)
    }

    @Test fun `a non-routable assert selector (regex) throws BackendUnsupportedOperation`() {
        val b = backend(FakeDeviceProvider { resolved(1, 1, 10, 10) })
        val cmd = AssertConditionCommand(Condition(visible = ElementSelector(textRegex = "Item .*")))
        assertThrows<BackendUnsupportedOperation> { runBlocking { b.execute(cmd, ctx) } }
    }

    @Test fun `a non-routable tap selector (relative) throws BackendUnsupportedOperation`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = backend(fake)
        val sel = ElementSelector(idRegex = "btn", below = ElementSelector(textRegex = "Header"))
        assertThrows<BackendUnsupportedOperation> { runBlocking { b.execute(TapOnElementCommand(sel), ctx) } }
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

    @Test fun `evaluateCondition throws BackendUnsupportedOperation for a non-routable condition (DECISION FLAG - K2)`() {
        // DECISION FLAG (plan Task K2): a `when:`/assert guard device-core can't route now hard-fails
        // the flow instead of fabricating a `true` verdict — symmetric with the command path. If benign
        // `platform:` guards turn out common on device-core runs this may need a targeted carve-out;
        // it's a one-line change to flip back to `return true`.
        val b = backend(FakeDeviceProvider { absent() })
        assertThrows<BackendUnsupportedOperation> {
            runBlocking { b.evaluateCondition(Condition(scriptCondition = "x"), false, null, ctx) }
        }
    }

    // --- lifecycle ---

    @Test fun `open connects exactly once and close closes the device`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val b = DeviceCoreExecutionBackend(platform = Platform.ANDROID, appId = "com.x", providerFactory = { fake })
        b.open("com.x", null)
        runBlocking { b.execute(assertVisible("A"), ctx) }
        runBlocking { b.execute(assertVisible("B"), ctx) }
        assertThat(fake.connectCount).isEqualTo(1)

        b.close()
        assertThat(fake.closed).isTrue()
    }

    @Test fun `close is null-safe when open was never called`() {
        val b = DeviceCoreExecutionBackend(platform = Platform.ANDROID, appId = "com.x", providerFactory = { FakeDeviceProvider { absent() } })
        b.close() // must not throw
    }

    // --- platform-parametric provider + target selection + iOS app-binding (Task P1.1) ---

    @Test
    fun `iOS open connects IOS_SIM and binds bundleId via system property`() {
        System.clearProperty("devicecore.ios.bundleId")
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val backend = DeviceCoreExecutionBackend(
            platform = Platform.IOS,
            appId = "com.apple.Preferences",
            providerFactory = { fake },
        )
        backend.open(appId = "com.apple.Preferences", config = null)
        assertThat(fake.lastConnectedTarget?.target).isEqualTo(TargetId.IOS_SIM)
        assertThat(System.getProperty("devicecore.ios.bundleId")).isEqualTo("com.apple.Preferences")
    }

    @Test
    fun `Android open connects ANDROID_EMU and does not set iOS bundleId`() {
        System.clearProperty("devicecore.ios.bundleId")
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val backend = DeviceCoreExecutionBackend(
            platform = Platform.ANDROID,
            appId = "com.android.settings",
            providerFactory = { fake },
        )
        backend.open(appId = "com.android.settings", config = null)
        assertThat(fake.lastConnectedTarget?.target).isEqualTo(TargetId.ANDROID_EMU)
        assertThat(System.getProperty("devicecore.ios.bundleId")).isNull()
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

    // --- device-log / crash capture: owed capability, safe no-ops (Task 4.D2-T2) ---

    @Test fun `startDeviceLogCapture does not throw`() {
        val b = backend(FakeDeviceProvider { absent() })
        runBlocking { b.startDeviceLogCapture() } // must not throw
    }

    @Test fun `stopAndCollectDeviceLogs returns an empty list`() {
        val b = backend(FakeDeviceProvider { absent() })
        val result = runBlocking { b.stopAndCollectDeviceLogs(File("build/tmp/devicecore-test-logs")) }
        assertThat(result).isEmpty()
    }

    @Test fun `collectCrashArtifacts returns an empty list`() {
        val b = backend(FakeDeviceProvider { absent() })
        val result = runBlocking { b.collectCrashArtifacts("com.x", 0L, File("build/tmp/devicecore-test-logs")) }
        assertThat(result).isEmpty()
    }
}
