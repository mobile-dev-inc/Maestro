package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.ActionEvidence
import dev.mobile.devicecore.prototype.api.Actionability
import dev.mobile.devicecore.prototype.api.Device
import dev.mobile.devicecore.prototype.api.DeviceProvider
import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.EvidenceSource
import dev.mobile.devicecore.prototype.api.FoundVia
import dev.mobile.devicecore.prototype.api.InjectionUnavailable
import dev.mobile.devicecore.prototype.api.Locator
import dev.mobile.devicecore.prototype.api.Match
import dev.mobile.devicecore.prototype.api.Outcome
import dev.mobile.devicecore.prototype.api.Point
import dev.mobile.devicecore.prototype.api.Screen
import dev.mobile.devicecore.prototype.api.Selector
import dev.mobile.devicecore.prototype.api.Settle
import dev.mobile.devicecore.prototype.api.Signal
import dev.mobile.devicecore.prototype.api.TargetSelector

/**
 * In-memory [DeviceProvider] for the device-core backend tests. inspect() returns whatever
 * [evidenceFor] maps a [Selector] to; tap() records the tapped selector and returns a canned
 * [ActionEvidence]. No real device.
 */
class FakeDeviceProvider(
    // Invoked inside tap() before it returns; a test can throw here to simulate a device-core tap
    // failure (element not found / gesture rejected). Default is a no-op (tap always succeeds).
    // Declared before [evidenceFor] so the common `FakeDeviceProvider { evidence }` trailing-lambda
    // call still binds the lambda to [evidenceFor] (Kotlin binds a trailing lambda to the last param).
    private val onTap: (Selector) -> Unit = {},
    // When true, launchApp() throws InjectionUnavailable instead of recording the call — simulates a
    // device-core launch failure (target could not be brought up).
    private val launchFails: Boolean = false,
    // When set, launchApp() throws this instead of recording the call — simulates a throwable
    // device-core does NOT itself map (e.g. the ambiguous-serial IllegalStateException resolveSerial()
    // raises). Takes precedence over [launchFails].
    private val launchThrows: Throwable? = null,
    private val evidenceFor: (Selector) -> ElementEvidence,
) : DeviceProvider {
    var connectCount: Int = 0
    var lastConnectedTarget: TargetSelector? = null
    var lastInspectedSelector: Selector? = null
    var lastTappedSelector: Selector? = null
    var tapCount: Int = 0
    var closed: Boolean = false
    val launchedApps = mutableListOf<String>()

    override suspend fun connect(selector: TargetSelector): Device {
        connectCount++
        lastConnectedTarget = selector
        return object : Device {
            override val screen: Screen = object : Screen {
                override fun getById(value: String): Locator = locator(Selector.Id(value))
                override fun getByText(value: String, match: Match, ignoreCase: Boolean): Locator =
                    locator(Selector.Text(value, match, ignoreCase))
            }

            override suspend fun launchApp(appId: String) {
                launchThrows?.let { throw it }
                if (launchFails) {
                    throw InjectionUnavailable("fake launch failure for $appId")
                }
                launchedApps.add(appId)
            }

            override fun close() {
                closed = true
            }
        }
    }

    private fun locator(sel: Selector): Locator = object : Locator {
        override val selector: Selector = sel
        override suspend fun tap(): ActionEvidence {
            tapCount++
            lastTappedSelector = sel
            onTap(sel)
            return CANNED_TAP
        }

        override suspend fun inspect(): ElementEvidence {
            lastInspectedSelector = sel
            return evidenceFor(sel)
        }

        override fun nth(index: Int): Locator = locator(Selector.Nth(sel, index))
    }

    private companion object {
        private val ua = Signal(false, EvidenceSource.UNAVAILABLE)
        private val CANNED_TAP = ActionEvidence(
            actionId = "a",
            target = "t",
            outcome = Outcome.Acted(FoundVia.IMMEDIATE),
            actionability = Actionability(ua, ua, ua, ua, ua),
            delivered = Signal(true, EvidenceSource.MEASURED),
            settle = Settle(ua, ua),
            injectPoint = Point(10, 20),
            waitedMs = 0L,
        )
    }
}
