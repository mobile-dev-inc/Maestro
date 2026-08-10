package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.ActionEvidence
import dev.mobile.devicecore.prototype.api.Actionability
import dev.mobile.devicecore.prototype.api.Device
import dev.mobile.devicecore.prototype.api.DeviceProvider
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
import dev.mobile.devicecore.prototype.api.TargetSelector

/**
 * In-memory [DeviceProvider] for the device-core backend tests. inspect() returns whatever
 * [evidenceFor] maps a [Selector] to; tap() records the tapped selector and returns a canned
 * [ActionEvidence]. No real device.
 */
class FakeDeviceProvider(private val evidenceFor: (Selector) -> ElementEvidence) : DeviceProvider {
    var connectCount: Int = 0
    var lastConnectedTarget: TargetSelector? = null
    var lastInspectedSelector: Selector? = null
    var lastTappedSelector: Selector? = null
    var tapCount: Int = 0
    var closed: Boolean = false

    override suspend fun connect(selector: TargetSelector): Device {
        connectCount++
        lastConnectedTarget = selector
        return object : Device {
            override val screen: Screen = object : Screen {
                override fun getById(value: String): Locator = locator(Selector.Id(value))
                override fun getByText(value: String, match: Match, ignoreCase: Boolean): Locator =
                    locator(Selector.Text(value, match, ignoreCase))
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
