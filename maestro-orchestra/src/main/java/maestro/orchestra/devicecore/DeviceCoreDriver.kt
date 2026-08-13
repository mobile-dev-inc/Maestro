package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.ANDROID_EMU
import dev.mobile.devicecore.prototype.api.ActionEvidence
import dev.mobile.devicecore.prototype.api.AppId
import dev.mobile.devicecore.prototype.api.Device
import dev.mobile.devicecore.prototype.api.DeviceProvider
import dev.mobile.devicecore.prototype.api.ElementEvidence
import dev.mobile.devicecore.prototype.api.IOS_SIM
import dev.mobile.devicecore.prototype.api.Locator
import dev.mobile.devicecore.prototype.api.Screen
import dev.mobile.devicecore.prototype.api.Selector
import dev.mobile.devicecore.prototype.api.TargetId
import dev.mobile.devicecore.prototype.api.TargetSelector
import dev.mobile.devicecore.prototype.api.adaptors.android.AndroidDeviceProvider
import dev.mobile.devicecore.prototype.api.adaptors.ios.IosDeviceProvider
import kotlinx.coroutines.runBlocking
import maestro.MaestroException
import maestro.device.Platform
import maestro.orchestra.ElementSelector

/** Which device-core target a connect names, in Maestro's own terms. */
data class DeviceCoreTarget(val platform: Platform)

/**
 * The single seam the `maestro test` path uses to reach a device through device-core: four verbs
 * plus lifecycle. Everything the four-command vertical needs and nothing it doesn't — selector
 * translation, tap/assert verdicts, and error mapping are composed behind this, not exposed.
 *
 * `tap`/`assertVisibility` return an optional [ChosenElement] for the differential trace. A failing
 * assert throws [MaestroException.AssertionFailure]; a failed tap throws whatever
 * [DeviceCoreErrorMapper] maps its outcome to; an infra failure throws through
 * [DeviceCoreErrorMapper.mapInfraThrow]; an unsupported selector throws
 * [MaestroException.NotImplemented].
 */
interface DeviceCoreDriver {
    fun connect(target: DeviceCoreTarget, appId: String?)
    fun close()
    fun launchApp(appId: String)
    fun tap(selector: ElementSelector): ChosenElement?
    fun assertVisibility(selector: ElementSelector, mode: AssertMode): ChosenElement?
}

/**
 * The real driver over `dev.mobile.devicecore.prototype.api.*`. Blocking on the outside (the
 * `maestro test` path is blocking), suspending calls bridged with [runBlocking] at each verb.
 *
 * [providerFactory] builds the device-core [DeviceProvider] for a platform; the default wires the
 * real Android/iOS adaptors, and tests inject a fake. iOS carries its bundle id through the
 * `devicecore.ios.bundleId` system property set BEFORE connect (device-core reads it internally at
 * connect time; there is no per-connection bundleId parameter).
 */
class RealDeviceCoreDriver(
    private val providerFactory: (Platform) -> DeviceProvider = ::defaultProviderFor,
) : DeviceCoreDriver {

    private var device: Device? = null

    private val screen: Screen
        get() = (device ?: error("device-core driver used before connect()")).screen

    override fun connect(target: DeviceCoreTarget, appId: String?) {
        val targetSelector = when (target.platform) {
            Platform.ANDROID -> TargetSelector(TargetId.ANDROID_EMU)
            Platform.IOS -> {
                if (appId != null) System.setProperty("devicecore.ios.bundleId", appId)
                TargetSelector(TargetId.IOS_SIM)
            }
            Platform.WEB -> throw MaestroException.NotImplemented(
                "device-core has no web target"
            )
        }
        device = try {
            runBlocking { providerFactory(target.platform).connect(targetSelector) }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "connect")
        }
    }

    override fun close() {
        device?.close()
        device = null
    }

    override fun launchApp(appId: String) {
        val d = device ?: error("device-core driver used before connect()")
        try {
            runBlocking { d.launchApp(AppId(appId)) }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "launch $appId")
        }
    }

    override fun tap(selector: ElementSelector): ChosenElement? {
        val sel = SelectorTranslator.translate(selector)
        val action = try {
            runBlocking { screen.locatorFor(sel).tap() }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "tap ${selector.description()}")
        }
        DeviceCoreErrorMapper.tapOutcomeToException(action.outcome, selector.description())
            ?.let { throw it }
        return chosenElementOfAction(action, sel)
    }

    override fun assertVisibility(selector: ElementSelector, mode: AssertMode): ChosenElement? {
        val sel = SelectorTranslator.translate(selector)
        // Both the read (inspect) and the verdict (pass) can raise DeviceCoreUnavailable — inspect on
        // a transport failure, pass() on Ambiguous / Unavailable / a resolved-but-UNAVAILABLE-signal
        // element. Both are infra, so both go through mapInfraThrow -> DeviceUnreachableException. The
        // AssertionFailure on a clean false verdict is thrown AFTER the try so it keeps its own type.
        val pass = try {
            val evidence = runBlocking { screen.locatorFor(sel).inspect() }
            AssertVisibleVerdict.pass(evidence, mode) to evidence
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "assert ${selector.description()}")
        }
        val (passed, evidence) = pass
        if (!passed) {
            val verb = if (mode == AssertMode.VISIBLE) "visible" else "not visible"
            throw MaestroException.AssertionFailure(
                message = "Assertion is false: ${selector.description()} is $verb",
                hierarchyRoot = DeviceCoreErrorMapper.emptyHierarchy(),
                debugMessage = "device-core reported ${selector.description()} as " +
                    "${evidence.resolution} / visible=${evidence.actionability.visible} for mode $mode",
            )
        }
        return chosenElementOfEvidence(evidence, sel)
    }

    /**
     * Walks a device-core [Selector] onto [Screen] calls. Text/Id land directly on a getter; Nth
     * refines the locator built from its target. Any other [Selector] variant is a device-core
     * capability the four-command vertical hasn't wired — [MaestroException.NotImplemented], never a
     * silent fallback.
     */
    private fun Screen.locatorFor(sel: Selector): Locator = when (sel) {
        is Selector.Text -> getByText(sel.value, sel.match, sel.ignoreCase)
        is Selector.Id -> getById(sel.value)
        is Selector.Nth -> locatorFor(sel.target).nth(sel.index)
        else -> throw MaestroException.NotImplemented(
            "device-core locator for ${sel::class.simpleName}"
        )
    }

    private fun chosenElementOfAction(action: ActionEvidence, sel: Selector): ChosenElement {
        val point = action.injectPoint
        val d = describe(sel)
        return ChosenElement(
            x = 0,
            y = 0,
            width = 0,
            height = 0,
            centerX = point?.x ?: 0,
            centerY = point?.y ?: 0,
            text = d.text,
            resourceId = d.id,
            index = d.index,
        )
    }

    private fun chosenElementOfEvidence(evidence: ElementEvidence, sel: Selector): ChosenElement {
        val rect = evidence.bounds.value
        val d = describe(sel)
        val x = rect?.x ?: 0
        val y = rect?.y ?: 0
        val w = rect?.width ?: 0
        val h = rect?.height ?: 0
        return ChosenElement(
            x = x,
            y = y,
            width = w,
            height = h,
            centerX = if (rect != null) x + w / 2 else 0,
            centerY = if (rect != null) y + h / 2 else 0,
            text = evidence.matched.value?.text ?: d.text,
            resourceId = d.id,
            index = d.index,
        )
    }

    /** The selector's text / id / nth-index, unwrapping a [Selector.Nth] to its target. */
    private data class SelectorDescription(val text: String?, val id: String?, val index: Int?)

    private fun describe(sel: Selector): SelectorDescription = when (sel) {
        is Selector.Text -> SelectorDescription(text = sel.value, id = null, index = null)
        is Selector.Id -> SelectorDescription(text = null, id = sel.value, index = null)
        is Selector.Nth -> describe(sel.target).copy(index = sel.index)
        else -> SelectorDescription(text = null, id = null, index = null)
    }

    private companion object {
        private fun defaultProviderFor(platform: Platform): DeviceProvider = when (platform) {
            Platform.ANDROID -> AndroidDeviceProvider()
            Platform.IOS -> IosDeviceProvider()
            Platform.WEB -> throw MaestroException.NotImplemented("device-core has no web provider")
        }
    }
}
