package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.ActionEvidence
import dev.mobile.devicecore.prototype.api.Actionability
import dev.mobile.devicecore.prototype.api.AppId
import dev.mobile.devicecore.prototype.api.Device
import dev.mobile.devicecore.prototype.api.DeviceEnvError
import dev.mobile.devicecore.prototype.api.DeviceProvider
import dev.mobile.devicecore.prototype.api.Diagnostic
import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.EvidenceSource
import dev.mobile.devicecore.prototype.api.FoundVia
import dev.mobile.devicecore.prototype.api.Locator
import dev.mobile.devicecore.prototype.api.Match
import dev.mobile.devicecore.prototype.api.Outcome
import dev.mobile.devicecore.prototype.api.Point
import dev.mobile.devicecore.prototype.api.Screen
import dev.mobile.devicecore.prototype.api.Selector
import dev.mobile.devicecore.prototype.api.Settle
import dev.mobile.devicecore.prototype.api.Signal
import dev.mobile.devicecore.prototype.api.Sourced
import dev.mobile.devicecore.prototype.api.TargetSelector
import kotlinx.coroutines.delay

/**
 * In-memory [DeviceProvider] for the device-core driver tests. `inspect()` returns whatever
 * [evidenceFor] maps a [Selector] to; `tap()` records the tapped selector, runs [onTap] (which may
 * throw to simulate an infra failure), and returns an [ActionEvidence] whose [Outcome] is
 * [tapOutcome] (default [Outcome.Acted]). No real device.
 *
 * The failure/timing levers a test can pull:
 *  - [onTap] throws  -> a thrown-infra tap (gesture rejected).
 *  - [tapOutcome] returns [Outcome.Absent] -> a policy-negative tap (element not found).
 *  - [launchFails] -> `launchApp` throws instead of recording.
 *  - [evidenceFor] -> the read-side verdict for `inspect` (assertVisibility).
 *  - [delayMs] -> `tap`/`inspect` suspend for this long before returning, giving a retry/repeat
 *    loop's per-attempt work real wall-clock duration (see [delayMs]'s own doc for the cancellation
 *    caveat).
 */
class FakeDeviceProvider(
    // Invoked inside tap() before it returns; a test can throw here to simulate a device-core tap
    // failure (gesture rejected). Default is a no-op. Declared before [evidenceFor] so the common
    // `FakeDeviceProvider { evidence }` trailing-lambda call still binds the lambda to [evidenceFor].
    private val onTap: (Selector) -> Unit = {},
    // The policy Outcome tap() reports. Default is a successful Acted; a test returns Outcome.Absent
    // to drive the ElementNotFound path without throwing.
    private val tapOutcome: (Selector) -> Outcome = { Outcome.Acted(FoundVia.IMMEDIATE) },
    // When true, launchApp() throws DeviceEnvError.OperationFailed instead of recording the call —
    // exactly what device-core's launchApp throws on a failed launch (Api.kt), which the error
    // mapper turns into MaestroException.UnableToLaunchApp.
    private val launchFails: Boolean = false,
    // Suspends for this long (via kotlinx.coroutines.delay) before tap()/inspect() return. Default 0
    // (no-op). A test can use this to give a retry/repeat loop's per-attempt work real wall-clock
    // duration to interrupt — NOTE this delay runs inside RealDeviceGateway's own nested
    // `runBlocking { ... }` around each call, which is a job tree disjoint from whatever outer
    // coroutine (e.g. an outer `withTimeout`) invoked the flow, so an outer cancellation can NOT
    // interrupt an in-flight delay here; it only becomes observable at the next shared-tree
    // suspension point (e.g. Orchestra's own `yield()` calls) once this delay completes.
    private val delayMs: Long = 0,
    private val evidenceFor: (Selector) -> ElementEvidence,
) : DeviceProvider {
    var connectCount: Int = 0
    var lastConnectedTarget: TargetSelector? = null
    var lastInspectedSelector: Selector? = null
    var lastTappedSelector: Selector? = null
    var tapCount: Int = 0
    var closed: Boolean = false
    val launchedApps = mutableListOf<String>()

    /** Snapshot of `devicecore.ios.bundleId` taken AT connect() time, to prove set-before-connect
     *  ordering rather than merely that the property is set by the time the test asserts on it. */
    var bundleIdAtConnect: String? = null

    override suspend fun connect(selector: TargetSelector): Device {
        connectCount++
        lastConnectedTarget = selector
        bundleIdAtConnect = System.getProperty("devicecore.ios.bundleId")
        return object : Device {
            override val screen: Screen = object : Screen {
                override fun getById(value: String): Locator = locator(Selector.Id(value))
                override fun getByText(value: String, match: Match, ignoreCase: Boolean): Locator =
                    locator(Selector.Text(value, match, ignoreCase))
            }

            override suspend fun launchApp(appId: AppId) {
                if (launchFails) {
                    throw DeviceEnvError.OperationFailed(
                        capability = "launchApp",
                        detail = Sourced<Diagnostic>(null, EvidenceSource.UNAVAILABLE),
                        summary = "fake launch failure for ${appId.value}",
                    )
                }
                launchedApps.add(appId.value)
            }

            override suspend fun openLink(url: String) = Unit

            override suspend fun stopApp(appId: AppId) = Unit

            override fun close() {
                closed = true
            }
        }
    }

    private fun locator(sel: Selector): Locator = object : Locator {
        override val selector: Selector = sel

        override suspend fun tap(): ActionEvidence {
            if (delayMs > 0) delay(delayMs)
            tapCount++
            lastTappedSelector = sel
            onTap(sel)
            return CANNED_TAP.copy(outcome = tapOutcome(sel), target = sel.toString())
        }

        override suspend fun inspect(): ElementEvidence {
            if (delayMs > 0) delay(delayMs)
            lastInspectedSelector = sel
            return evidenceFor(sel)
        }

        override fun nth(index: Int): Locator = locator(Selector.Nth(sel, index))
    }

    private companion object {
        private val UA = Signal(false, EvidenceSource.UNAVAILABLE)
        private val CANNED_TAP = ActionEvidence(
            actionId = "a",
            target = "t",
            outcome = Outcome.Acted(FoundVia.IMMEDIATE),
            actionability = Actionability(UA, UA, UA, UA, UA),
            delivered = Signal(true, EvidenceSource.MEASURED),
            settle = Settle(UA, UA),
            injectPoint = Point(10, 20),
            waitedMs = 0L,
        )
    }
}
