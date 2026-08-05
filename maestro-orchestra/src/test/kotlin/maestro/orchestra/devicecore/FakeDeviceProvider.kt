package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.*

class FakeDeviceProvider(private val evidenceFor: (Selector) -> ElementEvidence) : DeviceProvider {
    var lastConnectedTarget: TargetSelector? = null
    var lastSelector: Selector? = null

    /** Snapshot of `devicecore.ios.bundleId` taken AT connect() time, to prove set-before-connect
     *  ordering rather than merely that the property is set by the time the test asserts on it. */
    var bundleIdAtConnect: String? = null

    override suspend fun connect(selector: TargetSelector): Device {
        lastConnectedTarget = selector
        bundleIdAtConnect = System.getProperty("devicecore.ios.bundleId")
        return object : Device {
            override val screen: Screen = object : Screen {
                override fun getById(value: String): Locator = locator(Selector.Id(value))
                override fun getByText(value: String, match: Match): Locator = locator(Selector.Text(value, match))
            }
        }
    }

    private fun locator(sel: Selector): Locator = object : Locator {
        override val selector: Selector = sel
        override suspend fun tap(): ActionEvidence = throw NotImplementedError("fake: no tap")
        override suspend fun inspect(): ElementEvidence { lastSelector = sel; return evidenceFor(sel) }
        override fun nth(index: Int): Locator = locator(Selector.Nth(sel, index))
    }
}
