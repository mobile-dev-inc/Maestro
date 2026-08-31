package maestro.orchestra.devicecore

import dev.mobile.devicecore.prototype.api.ANDROID_EMU
import dev.mobile.devicecore.prototype.api.ActionEvidence
import dev.mobile.devicecore.prototype.api.AppId
import dev.mobile.devicecore.prototype.api.Device
import dev.mobile.devicecore.prototype.api.DeviceProvider
import dev.mobile.devicecore.prototype.api.Direction
import dev.mobile.devicecore.prototype.api.IOS_SIM
import dev.mobile.devicecore.prototype.api.Locator
import dev.mobile.devicecore.prototype.api.Screen
import dev.mobile.devicecore.prototype.api.Key
import dev.mobile.devicecore.prototype.api.Relation
import dev.mobile.devicecore.prototype.api.Selector
import dev.mobile.devicecore.prototype.api.Travel
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
import maestro.ScrollDirection
import maestro.SwipeDirection
import maestro.TapRepeat
import maestro.device.CapturedDeviceArtifact
import maestro.device.DeviceOrientation
import maestro.device.Platform
import maestro.orchestra.ElementSelector
import okio.Sink
import java.io.File

/*
 * Seam contract: this seam speaks Maestro's existing vocabulary and coins no new terms. device-core
 * is the canonical source of device behavior; the seam's only job is to express that behavior in the
 * verbs Orchestra already knows.
 */

/** Which visibility question an [DeviceGateway.assertVisibility] answers. */
enum class AssertMode { VISIBLE, NOT_VISIBLE }

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
 * `assertVisibility` is a WAITED verb: VISIBLE routes to device-core's `Locator.waitFor(timeoutMs)`
 * and reads the pass/fail verdict off the returned `Outcome`; NOT_VISIBLE throws
 * [MaestroException.NotImplemented] (device-core ships no `waitFor(GONE)` verb yet).
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
 * throws [MaestroException.NotImplemented] for all of them. Orchestra is a live production consumer
 * of this seam today; every verb device-core has not built yet throws `NotImplemented` from
 * [RealDeviceGateway].
 */
interface DeviceGateway {
    fun connect(target: DeviceCoreTarget, appId: String?)
    fun close()
    /** [arguments] = legacy launchApp's launchArguments, threaded to device-core's typed launch
     *  arguments (`am start` extras on Android). Map<String, Any> on both sides — no translation. */
    fun launchApp(appId: String, arguments: Map<String, Any> = emptyMap())

    /** [timeoutMs] is legacy tapOn's appearance wait, threaded straight through to device-core's
     *  Locator.tap budget — Orchestra passes its single adjusted lookupTimeoutMs (see
     *  LOOKUP_TIMEOUT_DERIVATION.md). 0 = act on the present screen, one observation. */
    fun tap(selector: ElementSelector, timeoutMs: Long = 0L): ChosenElement?

    /** legacy tapOn's longPress modifier → device-core Locator.longPress. Same appearance-and-gate
     *  budget as tap; the press is held past the system long-press threshold by the strategy. */
    fun longPress(selector: ElementSelector, timeoutMs: Long = 0L): ChosenElement? = notImplemented("longPress")
    fun assertVisibility(selector: ElementSelector, mode: AssertMode, timeoutMs: Long): ChosenElement?

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

    /** Legacy scrollUntilVisible, served whole by device-core's `Locator.scrollTo` — a swipe loop
     *  bounded by [timeoutMs] that stops when the locator resolves. The scroll mechanics (cadence,
     *  distance, settle) are the strategy's, not the caller's. */
    fun scrollUntilVisible(selector: ElementSelector, direction: ScrollDirection, timeoutMs: Long): ChosenElement? =
        notImplemented("scrollUntilVisible")

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

    override fun launchApp(appId: String, arguments: Map<String, Any>) {
        val d = device ?: error("device-core driver used before connect()")
        try {
            runBlocking { d.launchApp(AppId(appId), arguments) }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "launch $appId")
        }
    }

    override fun clearAppState(appId: String) {
        val d = device ?: error("device-core driver used before connect()")
        try {
            runBlocking { d.clearState(AppId(appId)) }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "clearState $appId")
        }
    }

    override fun setPermissions(appId: String, permissions: Map<String, String>) {
        val d = device ?: error("device-core driver used before connect()")
        try {
            runBlocking { d.setPermission(AppId(appId), permissions) }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "setPermissions $appId")
        }
    }

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        // device-core's Device.openLink(url) is a plain VIEW-intent open of the url. It does not
        // scope by appId, force a browser, or auto-verify — a browser-forced open has no device-core
        // verb, so it walls rather than silently routing through the default handler. (appId and
        // autoVerify are 2.x hints device-core does not model; the url is opened as-is.)
        if (browser) {
            throw MaestroException.NotImplemented("openLink browser=true is not served by device-core")
        }
        val d = device ?: error("device-core driver used before connect()")
        try {
            runBlocking { d.openLink(link) }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "openLink $link")
        }
    }

    override fun tap(selector: ElementSelector, timeoutMs: Long): ChosenElement? {
        val sel = SelectorTranslator.translate(selector)
        val action = try {
            runBlocking { screen.locatorFor(sel).tap(timeoutMs) }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "tap ${selector.description()}")
        }
        DeviceCoreErrorMapper.tapOutcomeToException(action.outcome, selector.description())
            ?.let { throw it }
        return chosenElementOfAction(action, sel)
    }

    override fun longPress(selector: ElementSelector, timeoutMs: Long): ChosenElement? {
        val sel = SelectorTranslator.translate(selector)
        val action = try {
            runBlocking { screen.locatorFor(sel).longPress(timeoutMs) }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "longPress ${selector.description()}")
        }
        DeviceCoreErrorMapper.tapOutcomeToException(action.outcome, selector.description())
            ?.let { throw it }
        return chosenElementOfAction(action, sel)
    }

    override fun inputText(text: String) {
        // device-core's inputText ignores the selector and writes to the FOCUSED editable node
        // (FocusedSetTextInputTextStrategy) — matching legacy's targetless inputText, whose focus is
        // set by the preceding tapOn. So a sentinel locator is handed in; the selector is never read.
        val action = try {
            runBlocking { screen.locatorFor(Selector.Id("")).inputText(text) }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "inputText")
        }
        DeviceCoreErrorMapper.tapOutcomeToException(action.outcome, "inputText")?.let { throw it }
    }

    override fun backPress() {
        val action = try {
            runBlocking { screen.back() }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "back")
        }
        DeviceCoreErrorMapper.tapOutcomeToException(action.outcome, "back")?.let { throw it }
    }

    override fun pressKey(code: KeyCode, waitForAppToSettle: Boolean) {
        val key = code.toDeviceCore()   // walls a KeyCode device-core's Key enum doesn't cover
        val action = try {
            runBlocking { screen.pressKey(key) }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "pressKey ${code.description}")
        }
        DeviceCoreErrorMapper.tapOutcomeToException(action.outcome, "pressKey ${code.description}")?.let { throw it }
    }

    /** Maestro KeyCode -> device-core Key. device-core's Key enum is the ten system keys it realizes
     *  (Api.kt); a KeyCode outside that set (BACK — use back(); ESCAPE/POWER/TAB/TV/media/remote-*)
     *  walls honestly rather than aliasing onto the nearest key. */
    private fun KeyCode.toDeviceCore(): Key = when (this) {
        KeyCode.ENTER -> Key.ENTER
        KeyCode.BACKSPACE -> Key.BACKSPACE
        KeyCode.HOME -> Key.HOME
        KeyCode.LOCK -> Key.LOCK
        KeyCode.VOLUME_UP -> Key.VOLUME_UP
        KeyCode.VOLUME_DOWN -> Key.VOLUME_DOWN
        KeyCode.REMOTE_UP -> Key.DPAD_UP
        KeyCode.REMOTE_DOWN -> Key.DPAD_DOWN
        KeyCode.REMOTE_LEFT -> Key.DPAD_LEFT
        KeyCode.REMOTE_RIGHT -> Key.DPAD_RIGHT
        else -> throw MaestroException.NotImplemented("pressKey ${this.description}")
    }

    /** Targetless directional swipe, served by device-core's `Screen.swipe(Travel)`. Only the
     *  direction-only form is served; a swipe with explicit start/end points or relative anchors
     *  (or one anchored to an element — Orchestra routes those here too, dropping the element) has
     *  no device-core verb yet, so it walls honestly rather than degrading to a plain directional. */
    override fun swipe(
        swipeDirection: SwipeDirection?,
        startPoint: Point?,
        endPoint: Point?,
        startRelative: String?,
        endRelative: String?,
        duration: Long,
        waitToSettleTimeoutMs: Int?,
    ) {
        if (startPoint != null || endPoint != null || startRelative != null || endRelative != null) {
            throw MaestroException.NotImplemented(
                "device-core serves a targetless directional swipe only (no point/relative anchor)"
            )
        }
        val travel = (swipeDirection
            ?: throw MaestroException.NotImplemented("swipe requires a direction")).toDeviceCore()
        val action = try {
            runBlocking { screen.swipe(travel) }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "swipe $swipeDirection")
        }
        DeviceCoreErrorMapper.tapOutcomeToException(action.outcome, "swipe $swipeDirection")?.let { throw it }
    }

    /** Maestro SwipeDirection -> device-core Travel (both are the four cardinal directions). */
    private fun SwipeDirection.toDeviceCore(): Travel = when (this) {
        SwipeDirection.UP -> Travel.UP
        SwipeDirection.DOWN -> Travel.DOWN
        SwipeDirection.LEFT -> Travel.LEFT
        SwipeDirection.RIGHT -> Travel.RIGHT
    }

    override fun scrollUntilVisible(selector: ElementSelector, direction: ScrollDirection, timeoutMs: Long): ChosenElement? {
        val sel = SelectorTranslator.translate(selector)
        val action = try {
            runBlocking { screen.locatorFor(sel).scrollTo(direction.toDeviceCore(), timeoutMs = timeoutMs) }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "scrollUntilVisible ${selector.description()}")
        }
        DeviceCoreErrorMapper.tapOutcomeToException(action.outcome, selector.description())
            ?.let { throw it }
        return chosenElementOfAction(action, sel)
    }

    /** Both vocabularies are EYE-movement semantics — device-core Api.kt: "UP moves the viewer's
     *  eye up the content"; Maestro's scrollUntilVisible direction names where the target lies.
     *  A name-for-name mapping, pinned by DirectionTranslationTest rather than assumed. */
    private fun ScrollDirection.toDeviceCore(): Direction = when (this) {
        ScrollDirection.UP -> Direction.UP
        ScrollDirection.DOWN -> Direction.DOWN
        ScrollDirection.LEFT -> Direction.LEFT
        ScrollDirection.RIGHT -> Direction.RIGHT
    }

    override fun assertVisibility(selector: ElementSelector, mode: AssertMode, timeoutMs: Long): ChosenElement? {
        if (mode == AssertMode.NOT_VISIBLE) {
            // device-core has no waitFor(GONE) verb yet — never answer a wait-question with a racy read.
            throw MaestroException.NotImplemented("assertNotVisible / waitFor(GONE)")
        }
        val sel = SelectorTranslator.translate(selector)
        val evidence = try {
            // iOS IosLocator.waitFor throws NotImplementedError -> mapped to NotImplemented (no platform branch).
            runBlocking { screen.locatorFor(sel).waitFor(timeoutMs) }
        } catch (t: Throwable) {
            throw DeviceCoreErrorMapper.mapInfraThrow(t, "assertVisible ${selector.description()}")
        }
        WaitOutcomeVerdict.toException(evidence, selector.description(), timeoutMs)?.let { throw it }
        return chosenElementOfAction(evidence, sel)
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
        is Selector.Relative -> locatorFor(sel.target).let { base ->
            when (sel.relation) {
                Relation.ABOVE -> base.above(sel.anchor)
                Relation.BELOW -> base.below(sel.anchor)
                Relation.LEFT_OF -> base.leftOf(sel.anchor)
                Relation.RIGHT_OF -> base.rightOf(sel.anchor)
            }
        }
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

    /** The selector's text / id / nth-index, unwrapping a [Selector.Nth] to its target. */
    private data class SelectorDescription(val text: String?, val id: String?, val index: Int?)

    private fun describe(sel: Selector): SelectorDescription = when (sel) {
        is Selector.Text -> SelectorDescription(text = sel.value, id = null, index = null)
        is Selector.Id -> SelectorDescription(text = null, id = sel.value, index = null)
        is Selector.Nth -> describe(sel.target).copy(index = sel.index)
        is Selector.Relative -> describe(sel.target)
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
