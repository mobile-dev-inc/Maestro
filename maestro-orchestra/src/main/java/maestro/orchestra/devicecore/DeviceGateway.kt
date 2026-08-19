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
import maestro.device.CapturedDeviceArtifact
import maestro.device.DeviceOrientation
import maestro.device.Platform
import maestro.orchestra.ElementSelector
import okio.Sink
import java.io.File

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
 * interface grows to cover every device operation Orchestra performs, but [RealDeviceGateway]
 * throws [MaestroException.NotImplemented] for all of them until a later task repoints Orchestra
 * onto this seam and wires the real device-core call — this task changes no behavior.
 */
interface DeviceGateway {
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
    fun hierarchy(): Nothing = notImplemented("hierarchy")

    fun takeScreenshot(out: Sink, compressed: Boolean, cropOn: ElementSelector? = null): Unit =
        notImplemented("takeScreenshot")
    fun startScreenRecording(out: Sink): ScreenRecording = notImplemented("startScreenRecording")

    // --- Roadmap: device-log / crash-report capture (debug-artifact device reads) ---
    // Default-bodied (throwing) rather than abstract: only [ArtifactsGenerator] reaches for these,
    // best-effort and swallowed, so an unwired backend surfacing NotImplemented is the intended
    // outcome — and defaults spare every existing fake driver three empty overrides. A backend that
    // CAN capture logs (or a test fake) overrides them.

    fun startDeviceLogCapture(): Unit = notImplemented("startDeviceLogCapture")

    fun stopAndCollectDeviceLogs(outputDir: File): List<CapturedDeviceArtifact> =
        notImplemented("stopAndCollectDeviceLogs")

    fun collectCrashArtifacts(appId: String?, flowStartMs: Long, outputDir: File): List<CapturedDeviceArtifact> =
        notImplemented("collectCrashArtifacts")

    // --- Roadmap: text / keys ---

    fun inputText(text: String): Unit = notImplemented("inputText")
    fun eraseText(charactersToErase: Int): Unit = notImplemented("eraseText")
    fun pressKey(code: KeyCode, waitForAppToSettle: Boolean = true): Unit = notImplemented("pressKey")
    fun backPress(): Unit = notImplemented("backPress")
    fun hideKeyboard(): Unit = notImplemented("hideKeyboard")
    fun isKeyboardVisible(): Boolean = notImplemented("isKeyboardVisible")

    // --- Roadmap: gestures ---

    fun swipe(
        swipeDirection: SwipeDirection? = null,
        startPoint: Point? = null,
        endPoint: Point? = null,
        startRelative: String? = null,
        endRelative: String? = null,
        duration: Long,
        waitToSettleTimeoutMs: Int? = null,
    ): Unit = notImplemented("swipe")

    fun swipe(swipeDirection: SwipeDirection, startPoint: Point, durationMs: Long, waitToSettleTimeoutMs: Int?): Unit =
        notImplemented("swipe")

    fun swipeFromCenter(swipeDirection: SwipeDirection, durationMs: Long, waitToSettleTimeoutMs: Int?): Unit =
        notImplemented("swipeFromCenter")

    fun scrollVertical(): Unit = notImplemented("scrollVertical")

    fun tapOnRelative(
        percentX: Int,
        percentY: Int,
        retryIfNoChange: Boolean = false,
        longPress: Boolean = false,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null,
    ): Unit = notImplemented("tapOnRelative")

    fun tapOnPoint(
        x: Int,
        y: Int,
        retryIfNoChange: Boolean = false,
        longPress: Boolean = false,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null,
    ): Unit = notImplemented("tapOnPoint")

    // --- Roadmap: settle / animation ---

    fun waitForAnimationToEnd(timeout: String?): Unit = notImplemented("waitForAnimationToEnd")

    /**
     * Closest sensible equivalent to `Maestro.waitForAppToSettle`, which takes/returns
     * `maestro.ViewHierarchy` — a type this migration is deleting. Drops the `initialHierarchy`
     * parameter and the `ViewHierarchy?` return; device-core's own settle signal (once wired) will
     * decide "settled" without Maestro-side hierarchy diffing.
     */
    fun waitForAppToSettle(appId: String? = null, waitToSettleTimeoutMs: Int? = null): Unit =
        notImplemented("waitForAppToSettle")

    // --- Roadmap: links / media ---

    fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean): Unit = notImplemented("openLink")
    fun addMedia(fileNames: List<String>): Unit = notImplemented("addMedia")

    // --- Roadmap: app lifecycle / state ---

    fun clearAppState(appId: String): Unit = notImplemented("clearAppState")
    fun clearKeychain(): Unit = notImplemented("clearKeychain")
    fun stopApp(appId: String): Unit = notImplemented("stopApp")
    fun killApp(appId: String): Unit = notImplemented("killApp")
    fun setPermissions(appId: String, permissions: Map<String, String>): Unit = notImplemented("setPermissions")

    // --- Roadmap: device state ---

    fun setLocation(latitude: String, longitude: String): Unit = notImplemented("setLocation")
    fun setOrientation(orientation: DeviceOrientation, waitForAppToSettle: Boolean = true): Unit =
        notImplemented("setOrientation")
    fun setAirplaneModeState(enabled: Boolean): Unit = notImplemented("setAirplaneModeState")
    fun isAirplaneModeEnabled(): Boolean = notImplemented("isAirplaneModeEnabled")
    fun setDarkModeState(enabled: Boolean): Unit = notImplemented("setDarkModeState")
    fun isDarkModeEnabled(): Boolean = notImplemented("isDarkModeEnabled")
    fun setAndroidChromeDevToolsEnabled(enabled: Boolean): Unit = notImplemented("setAndroidChromeDevToolsEnabled")

    /**
     * A real device-core roundtrip for device metrics — distinct from the session-known platform
     * wired separately (W1.2), which never touches the device.
     */
    fun deviceInfo(): DeviceInfo = notImplemented("deviceInfo")
}

/** Uniform throw for every unbuilt gateway verb. */
private fun notImplemented(capability: String): Nothing =
    throw MaestroException.NotImplemented("device-core gateway does not yet implement $capability")

/**
 * The real driver over `dev.mobile.devicecore.prototype.api.*`. Blocking on the outside (the
 * `maestro test` path is blocking), suspending calls bridged with [runBlocking] at each verb.
 *
 * [providerFactory] builds the device-core [DeviceProvider] for a platform; the default wires the
 * real Android/iOS adaptors, and tests inject a fake. iOS carries its bundle id through the
 * `devicecore.ios.bundleId` system property set BEFORE connect (device-core reads it internally at
 * connect time; there is no per-connection bundleId parameter).
 */
class RealDeviceGateway(
    private val providerFactory: (Platform) -> DeviceProvider = ::defaultProviderFor,
) : DeviceGateway {

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

    // --- Every other verb inherits its throwing default from the interface (see [notImplemented]).

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
