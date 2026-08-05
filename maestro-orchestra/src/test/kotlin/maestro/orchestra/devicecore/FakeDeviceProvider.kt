package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.*

class FakeDeviceProvider(private val evidenceFor: (Selector) -> ElementEvidence) : DeviceProvider {
    var lastConnectedTarget: TargetSelector? = null
    var lastSelector: Selector? = null

    override suspend fun connect(selector: TargetSelector): Device {
        lastConnectedTarget = selector
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
