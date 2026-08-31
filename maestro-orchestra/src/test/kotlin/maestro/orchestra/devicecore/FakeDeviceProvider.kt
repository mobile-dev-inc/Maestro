package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.AbsentVia
import dev.mobile.devicecore.prototype.api.ActionEvidence
import dev.mobile.devicecore.prototype.api.Actionability
import dev.mobile.devicecore.prototype.api.AppId
import dev.mobile.devicecore.prototype.api.Device
import dev.mobile.devicecore.prototype.api.DeviceEnvError
import dev.mobile.devicecore.prototype.api.DeviceProvider
import dev.mobile.devicecore.prototype.api.Direction
import dev.mobile.devicecore.prototype.api.Relation
import dev.mobile.devicecore.prototype.api.Travel
import dev.mobile.devicecore.prototype.api.Diagnostic
import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.Key
import dev.mobile.devicecore.prototype.api.EvidenceSource
import dev.mobile.devicecore.prototype.api.FoundVia
import dev.mobile.devicecore.prototype.api.Locator
import dev.mobile.devicecore.prototype.api.Match
import dev.mobile.devicecore.prototype.api.Outcome
import dev.mobile.devicecore.prototype.api.Point
import dev.mobile.devicecore.prototype.api.Resolution
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
    // When true, clearState() throws DeviceEnvError.OperationFailed — what device-core's clearState
    // throws on a failed clear (Api.kt) — so a test can drive the launchApp-modifier failure path.
    private val clearStateFails: Boolean = false,
    // Suspends for this long (via kotlinx.coroutines.delay) before tap()/inspect() return. Default 0
    // (no-op). A test can use this to give a retry/repeat loop's per-attempt work real wall-clock
    // duration to interrupt — NOTE this delay runs inside RealDeviceGateway's own nested
    // `runBlocking { ... }` around each call, which is a job tree disjoint from whatever outer
    // coroutine (e.g. an outer `withTimeout`) invoked the flow, so an outer cancellation can NOT
    // interrupt an in-flight delay here; it only becomes observable at the next shared-tree
    // suspension point (e.g. Orchestra's own `yield()` calls) once this delay completes.
    private val delayMs: Long = 0,
    // The waited-verb Outcome. Default null -> derive from evidenceFor (keeps existing VISIBLE
    // asserts green when they route through waitFor). A test that drives the waited verdict directly
    // passes an explicit lambda. Declared before [evidenceFor] so the trailing-lambda call still
    // binds the lambda to [evidenceFor].
    private val waitOutcome: ((Selector) -> Outcome)? = null,
    private val evidenceFor: (Selector) -> ElementEvidence,
) : DeviceProvider {
    var connectCount: Int = 0
    var lastConnectedTarget: TargetSelector? = null
    var lastInspectedSelector: Selector? = null
    var lastWaitedSelector: Selector? = null
    var lastTappedSelector: Selector? = null
    var tapCount: Int = 0
    var longPressCount: Int = 0
    var lastLongPressedSelector: Selector? = null
    var lastInputText: String? = null
    var backCount: Int = 0
    var lastPressedKey: Key? = null
    var lastSwipe: Travel? = null
    var lastOpenedLink: String? = null
    var closed: Boolean = false
    val launchedApps = mutableListOf<String>()

    /** Every device-lifecycle call in arrival order ("clearState:<app>", "setPermission:<app>:<grants>",
     *  "launchApp:<app>") — the order is the semantic contract for launchApp modifiers (clear resets
     *  grants, so grant-after-clear), so tests assert on this list, not on the per-verb lists alone. */
    val deviceCalls = mutableListOf<String>()
    val clearedApps = mutableListOf<String>()
    val grantedPermissions = mutableListOf<Pair<String, Map<String, String>>>()

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
                override suspend fun back(): ActionEvidence {
                    backCount++
                    return CANNED_TAP.copy(target = "back")
                }
                override suspend fun pressKey(key: Key): ActionEvidence {
                    lastPressedKey = key
                    return CANNED_TAP.copy(target = "pressKey:$key")
                }
                override suspend fun swipe(travel: Travel): ActionEvidence {
                    lastSwipe = travel
                    return CANNED_TAP.copy(target = "swipe:$travel")
                }
            }

            override suspend fun launchApp(appId: AppId, arguments: Map<String, Any>) {
                if (launchFails) {
                    throw DeviceEnvError.OperationFailed(
                        capability = "launchApp",
                        detail = Sourced<Diagnostic>(null, EvidenceSource.UNAVAILABLE),
                        summary = "fake launch failure for ${appId.value}",
                    )
                }
                launchedApps.add(appId.value)
                deviceCalls.add("launchApp:${appId.value}")
            }

            override suspend fun clearState(appId: AppId) {
                if (clearStateFails) {
                    throw DeviceEnvError.OperationFailed(
                        capability = "clearState",
                        detail = Sourced<Diagnostic>(null, EvidenceSource.UNAVAILABLE),
                        summary = "fake clearState failure for ${appId.value}",
                    )
                }
                clearedApps.add(appId.value)
                deviceCalls.add("clearState:${appId.value}")
            }

            override suspend fun setPermission(appId: AppId, grants: Map<String, String>) {
                grantedPermissions.add(appId.value to grants)
                deviceCalls.add("setPermission:${appId.value}:$grants")
            }

            override suspend fun openLink(url: String) {
                lastOpenedLink = url
            }

            override suspend fun stopApp(appId: AppId) = Unit

            override fun close() {
                closed = true
            }
        }
    }

    private fun locator(sel: Selector): Locator = object : Locator {
        override val selector: Selector = sel

        // Roadmap verb no Maestro-side gateway calls yet: throw device-core's reserved roadmap
        // throw, same as an unbuilt strategy would, so a test that reaches it walls honestly.
        override suspend fun scrollTo(direction: Direction, minVisiblePercent: Int, timeoutMs: Long): ActionEvidence =
            throw NotImplementedError("scrollTo")

        override fun above(anchor: Selector): Locator = locator(Selector.Relative(sel, anchor, Relation.ABOVE))
        override fun below(anchor: Selector): Locator = locator(Selector.Relative(sel, anchor, Relation.BELOW))
        override fun leftOf(anchor: Selector): Locator = locator(Selector.Relative(sel, anchor, Relation.LEFT_OF))
        override fun rightOf(anchor: Selector): Locator = locator(Selector.Relative(sel, anchor, Relation.RIGHT_OF))

        override suspend fun tap(timeoutMs: Long): ActionEvidence {
            if (delayMs > 0) delay(delayMs)
            tapCount++
            lastTappedSelector = sel
            onTap(sel)
            return CANNED_TAP.copy(outcome = tapOutcome(sel), target = sel.toString())
        }

        override suspend fun longPress(timeoutMs: Long): ActionEvidence {
            longPressCount++
            lastLongPressedSelector = sel
            return CANNED_TAP.copy(outcome = tapOutcome(sel), target = sel.toString())
        }

        override suspend fun inputText(text: String): ActionEvidence {
            lastInputText = text
            return CANNED_TAP.copy(target = "inputText:$text")
        }

        override suspend fun inspect(): ElementEvidence {
            if (delayMs > 0) delay(delayMs)
            lastInspectedSelector = sel
            return evidenceFor(sel)
        }

        override suspend fun waitFor(timeoutMs: Long): ActionEvidence {
            if (delayMs > 0) delay(delayMs)
            lastWaitedSelector = sel
            // Default: derive the Outcome from the same seeded evidence inspect() uses, so existing
            // VISIBLE asserts keep their verdict when they route through waitFor instead of inspect.
            // A test that drives the waited verdict directly passes an explicit waitOutcome lambda.
            val outcome = waitOutcome?.invoke(sel) ?: outcomeFromEvidence(evidenceFor(sel))
            return CANNED_TAP.copy(outcome = outcome, target = sel.toString())
        }

        override fun nth(index: Int): Locator = locator(Selector.Nth(sel, index))
    }

    private companion object {
        private fun outcomeFromEvidence(ev: ElementEvidence): Outcome = when (ev.resolution) {
            is Resolution.Resolved ->
                if (ev.actionability.visible.value) Outcome.Acted(FoundVia.IMMEDIATE)
                else Outcome.Blocked(detail = "resolved but not visible")
            else -> Outcome.Absent(AbsentVia.CAP_WHILE_QUIET, capMs = 0L)
        }

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
