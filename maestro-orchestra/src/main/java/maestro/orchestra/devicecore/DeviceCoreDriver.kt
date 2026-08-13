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
import maestro.DeviceInfo
import maestro.KeyCode
import maestro.MaestroException
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TapRepeat
import maestro.device.DeviceOrientation
import maestro.device.Platform
import maestro.orchestra.ElementSelector
import okio.Sink

/**
 * Which device-core target a connect names, in Maestro's own terms. [serial] is the concrete
 * resolved device serial in adb's own format (e.g. `emulator-5554`) — what device-core's
 * [dev.mobile.devicecore.prototype.shared.provision.android.AdbSerialResolver] matches against to
 * disambiguate when more than one device is attached. Null means "let device-core pick" (the
 * single-device case).
 */
data class DeviceCoreTarget(val platform: Platform, val serial: String? = null)

/**
 * The single seam the `maestro test` path uses to reach a device through device-core. Selector
 * translation, tap/assert verdicts, and error mapping are composed behind this, not exposed.
 *
 * `tap`/`assertVisibility` return an optional [ChosenElement] for the differential trace. A failing
 * assert throws [MaestroException.AssertionFailure]; a failed tap throws whatever
 * [DeviceCoreErrorMapper] maps its outcome to; an infra failure throws through
 * [DeviceCoreErrorMapper.mapInfraThrow]; an unsupported selector throws
 * [MaestroException.NotImplemented].
 *
 * `connect`/`close`/`launchApp`/`tap`/`assertVisibility` are the five verbs the four-command
 * vertical (Spec A) actually wired to device-core. Every other method below is a ROADMAP verb: the
 * interface grows to cover every device operation Orchestra performs, but [RealDeviceCoreDriver]
 * throws [MaestroException.NotImplemented] for all of them until a later task repoints Orchestra
 * onto this seam and wires the real device-core call — this task changes no behavior.
 */
interface DeviceCoreDriver {
    fun connect(target: DeviceCoreTarget, appId: String?)
    fun close()
    fun launchApp(appId: String)
    fun tap(selector: ElementSelector): ChosenElement?
    fun assertVisibility(selector: ElementSelector, mode: AssertMode): ChosenElement?

    // --- Roadmap: hierarchy / screenshot / recording ---

    /**
     * A device-core hierarchy representation, or throws. Device-core's `Screen` API (as of this
     * task) exposes no hierarchy/dump type at all — only [dev.mobile.devicecore.prototype.api.Screen.getById]
     * / [dev.mobile.devicecore.prototype.api.Screen.getByText] locators — so there is no type to
     * return. Declared [Nothing]: it can only ever throw. Must NOT resolve to `maestro.TreeNode` /
     * `maestro.ViewHierarchy` — both are being deleted by this migration.
     */
    fun hierarchy(): Nothing

    fun takeScreenshot(out: Sink, compressed: Boolean, cropOn: ElementSelector? = null)
    fun startScreenRecording(out: Sink): ScreenRecording

    // --- Roadmap: text / keys ---

    fun inputText(text: String)
    fun eraseText(charactersToErase: Int)
    fun pressKey(code: KeyCode, waitForAppToSettle: Boolean = true)
    fun backPress()
    fun hideKeyboard()
    fun isKeyboardVisible(): Boolean

    // --- Roadmap: gestures ---

    fun swipe(
        swipeDirection: SwipeDirection? = null,
        startPoint: Point? = null,
        endPoint: Point? = null,
        startRelative: String? = null,
        endRelative: String? = null,
        duration: Long,
        waitToSettleTimeoutMs: Int? = null,
    )

    fun swipe(swipeDirection: SwipeDirection, startPoint: Point, durationMs: Long, waitToSettleTimeoutMs: Int?)
    fun swipeFromCenter(swipeDirection: SwipeDirection, durationMs: Long, waitToSettleTimeoutMs: Int?)
    fun scrollVertical()

    fun tapOnRelative(
        percentX: Int,
        percentY: Int,
        retryIfNoChange: Boolean = false,
        longPress: Boolean = false,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null,
    )

    fun tapOnPoint(
        x: Int,
        y: Int,
        retryIfNoChange: Boolean = false,
        longPress: Boolean = false,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null,
    )

    // --- Roadmap: settle / animation ---

    fun waitForAnimationToEnd(timeout: String?)

    /**
     * Closest sensible equivalent to `Maestro.waitForAppToSettle`, which takes/returns
     * `maestro.ViewHierarchy` — a type this migration is deleting. Drops the `initialHierarchy`
     * parameter and the `ViewHierarchy?` return; device-core's own settle signal (once wired) will
     * decide "settled" without Maestro-side hierarchy diffing.
     */
    fun waitForAppToSettle(appId: String? = null, waitToSettleTimeoutMs: Int? = null)

    // --- Roadmap: links / media ---

    fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean)
    fun addMedia(fileNames: List<String>)

    // --- Roadmap: app lifecycle / state ---

    fun clearAppState(appId: String)
    fun clearKeychain()
    fun stopApp(appId: String)
    fun killApp(appId: String)
    fun setPermissions(appId: String, permissions: Map<String, String>)

    // --- Roadmap: device state ---

    fun setLocation(latitude: String, longitude: String)
    fun setOrientation(orientation: DeviceOrientation, waitForAppToSettle: Boolean = true)
    fun setAirplaneModeState(enabled: Boolean)
    fun isAirplaneModeEnabled(): Boolean
    fun setDarkModeState(enabled: Boolean)
    fun isDarkModeEnabled(): Boolean
    fun setAndroidChromeDevToolsEnabled(enabled: Boolean)

    /**
     * A real device-core roundtrip for device metrics — distinct from the session-known platform
     * wired separately (W1.2), which never touches the device.
     */
    fun deviceInfo(): DeviceInfo
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
            Platform.ANDROID -> TargetSelector(TargetId.ANDROID_EMU, target.serial)
            Platform.IOS -> {
                if (appId != null) System.setProperty("devicecore.ios.bundleId", appId)
                TargetSelector(TargetId.IOS_SIM, target.serial)
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
                debugMessage = "device-core reported ${selector.description()} as " +
                    "${evidence.resolution} / visible=${evidence.actionability.visible} for mode $mode",
            )
        }
        return chosenElementOfEvidence(evidence, sel)
    }

    // --- Roadmap verbs: grown onto the interface with no behavior yet (see the interface doc).
    // Every one throws MaestroException.NotImplemented uniformly through [roadmap] — the leaf
    // capability, never a shared command-entry-point throw.

    override fun hierarchy(): Nothing = roadmap("hierarchy")
    override fun takeScreenshot(out: Sink, compressed: Boolean, cropOn: ElementSelector?) = roadmap("takeScreenshot")
    override fun startScreenRecording(out: Sink): ScreenRecording = roadmap("startScreenRecording")

    override fun inputText(text: String) = roadmap("inputText")
    override fun eraseText(charactersToErase: Int) = roadmap("eraseText")
    override fun pressKey(code: KeyCode, waitForAppToSettle: Boolean) = roadmap("pressKey")
    override fun backPress() = roadmap("backPress")
    override fun hideKeyboard() = roadmap("hideKeyboard")
    override fun isKeyboardVisible(): Boolean = roadmap("isKeyboardVisible")

    override fun swipe(
        swipeDirection: SwipeDirection?,
        startPoint: Point?,
        endPoint: Point?,
        startRelative: String?,
        endRelative: String?,
        duration: Long,
        waitToSettleTimeoutMs: Int?,
    ) = roadmap("swipe")

    override fun swipe(swipeDirection: SwipeDirection, startPoint: Point, durationMs: Long, waitToSettleTimeoutMs: Int?) =
        roadmap("swipe")

    override fun swipeFromCenter(swipeDirection: SwipeDirection, durationMs: Long, waitToSettleTimeoutMs: Int?) =
        roadmap("swipeFromCenter")

    override fun scrollVertical() = roadmap("scrollVertical")

    override fun tapOnRelative(
        percentX: Int,
        percentY: Int,
        retryIfNoChange: Boolean,
        longPress: Boolean,
        tapRepeat: TapRepeat?,
        waitToSettleTimeoutMs: Int?,
    ) = roadmap("tapOnRelative")

    override fun tapOnPoint(
        x: Int,
        y: Int,
        retryIfNoChange: Boolean,
        longPress: Boolean,
        tapRepeat: TapRepeat?,
        waitToSettleTimeoutMs: Int?,
    ) = roadmap("tapOnPoint")

    override fun waitForAnimationToEnd(timeout: String?) = roadmap("waitForAnimationToEnd")
    override fun waitForAppToSettle(appId: String?, waitToSettleTimeoutMs: Int?) = roadmap("waitForAppToSettle")

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) = roadmap("openLink")
    override fun addMedia(fileNames: List<String>) = roadmap("addMedia")

    override fun clearAppState(appId: String) = roadmap("clearAppState")
    override fun clearKeychain() = roadmap("clearKeychain")
    override fun stopApp(appId: String) = roadmap("stopApp")
    override fun killApp(appId: String) = roadmap("killApp")
    override fun setPermissions(appId: String, permissions: Map<String, String>) = roadmap("setPermissions")

    override fun setLocation(latitude: String, longitude: String) = roadmap("setLocation")
    override fun setOrientation(orientation: DeviceOrientation, waitForAppToSettle: Boolean) = roadmap("setOrientation")
    override fun setAirplaneModeState(enabled: Boolean) = roadmap("setAirplaneModeState")
    override fun isAirplaneModeEnabled(): Boolean = roadmap("isAirplaneModeEnabled")
    override fun setDarkModeState(enabled: Boolean) = roadmap("setDarkModeState")
    override fun isDarkModeEnabled(): Boolean = roadmap("isDarkModeEnabled")
    override fun setAndroidChromeDevToolsEnabled(enabled: Boolean) = roadmap("setAndroidChromeDevToolsEnabled")

    override fun deviceInfo(): DeviceInfo = roadmap("deviceInfo")

    /** Uniform throw for every roadmap verb — not yet wired to device-core. */
    private fun roadmap(capability: String): Nothing =
        throw MaestroException.NotImplemented("device-core driver does not yet implement $capability")

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
