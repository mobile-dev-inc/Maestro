package maestro

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import maestro.device.DeviceOrientation
import okio.Sink
import java.io.File

/**
 * Wraps a [Maestro] instance so that every blocking device-IO method runs on [Dispatchers.IO]
 * via [runInterruptible].
 *
 * When a coroutine using this wrapper is cancelled (e.g. via `withTimeout`), `runInterruptible`
 * interrupts the blocked thread via [Thread.interrupt], causing most blocking IO operations
 * (socket reads, gRPC calls) to throw [InterruptedException] or [java.io.InterruptedIOException].
 * This ensures the coroutine returns promptly even when device IO is stuck.
 */
class SuspendingMaestro(val delegate: Maestro) : AutoCloseable {

    val cachedDeviceInfo get() = delegate.cachedDeviceInfo
    val deviceName get() = delegate.deviceName

    @Deprecated("This function should be removed and its usages refactored. See issue #2031")
    suspend fun deviceInfo() = runInterruptible(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        delegate.deviceInfo()
    }

    suspend fun launchApp(
        appId: String,
        launchArguments: Map<String, Any> = emptyMap(),
        stopIfRunning: Boolean = true,
    ) = runInterruptible(Dispatchers.IO) {
        delegate.launchApp(appId, launchArguments, stopIfRunning)
    }

    suspend fun stopApp(appId: String) = runInterruptible(Dispatchers.IO) {
        delegate.stopApp(appId)
    }

    suspend fun killApp(appId: String) = runInterruptible(Dispatchers.IO) {
        delegate.killApp(appId)
    }

    suspend fun clearAppState(appId: String) = runInterruptible(Dispatchers.IO) {
        delegate.clearAppState(appId)
    }

    suspend fun setPermissions(appId: String, permissions: Map<String, String>) = runInterruptible(Dispatchers.IO) {
        delegate.setPermissions(appId, permissions)
    }

    suspend fun clearKeychain() = runInterruptible(Dispatchers.IO) {
        delegate.clearKeychain()
    }

    suspend fun backPress() = runInterruptible(Dispatchers.IO) {
        delegate.backPress()
    }

    suspend fun hideKeyboard() = runInterruptible(Dispatchers.IO) {
        delegate.hideKeyboard()
    }

    suspend fun isKeyboardVisible(): Boolean = runInterruptible(Dispatchers.IO) {
        delegate.isKeyboardVisible()
    }

    suspend fun swipe(
        swipeDirection: SwipeDirection? = null,
        startPoint: Point? = null,
        endPoint: Point? = null,
        startRelative: String? = null,
        endRelative: String? = null,
        duration: Long,
        waitToSettleTimeoutMs: Int? = null,
    ) = runInterruptible(Dispatchers.IO) {
        delegate.swipe(swipeDirection, startPoint, endPoint, startRelative, endRelative, duration, waitToSettleTimeoutMs)
    }

    suspend fun swipe(
        swipeDirection: SwipeDirection,
        uiElement: UiElement,
        durationMs: Long,
        waitToSettleTimeoutMs: Int?,
    ) = runInterruptible(Dispatchers.IO) {
        delegate.swipe(swipeDirection, uiElement, durationMs, waitToSettleTimeoutMs)
    }

    suspend fun swipeFromCenter(
        swipeDirection: SwipeDirection,
        durationMs: Long,
        waitToSettleTimeoutMs: Int?,
    ) = runInterruptible(Dispatchers.IO) {
        delegate.swipeFromCenter(swipeDirection, durationMs, waitToSettleTimeoutMs)
    }

    suspend fun scrollVertical() = runInterruptible(Dispatchers.IO) {
        delegate.scrollVertical()
    }

    suspend fun tap(
        element: UiElement,
        initialHierarchy: ViewHierarchy,
        retryIfNoChange: Boolean = false,
        waitUntilVisible: Boolean = false,
        longPress: Boolean = false,
        appId: String? = null,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null,
    ) = runInterruptible(Dispatchers.IO) {
        delegate.tap(element, initialHierarchy, retryIfNoChange, waitUntilVisible, longPress, appId, tapRepeat, waitToSettleTimeoutMs)
    }

    suspend fun tapOnRelative(
        percentX: Int,
        percentY: Int,
        retryIfNoChange: Boolean = false,
        longPress: Boolean = false,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null,
    ) = runInterruptible(Dispatchers.IO) {
        delegate.tapOnRelative(percentX, percentY, retryIfNoChange, longPress, tapRepeat, waitToSettleTimeoutMs)
    }

    suspend fun tap(
        x: Int,
        y: Int,
        retryIfNoChange: Boolean = false,
        longPress: Boolean = false,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null,
    ) = runInterruptible(Dispatchers.IO) {
        delegate.tap(x, y, retryIfNoChange, longPress, tapRepeat, waitToSettleTimeoutMs)
    }

    suspend fun pressKey(code: KeyCode, waitForAppToSettle: Boolean = true) = runInterruptible(Dispatchers.IO) {
        delegate.pressKey(code, waitForAppToSettle)
    }

    suspend fun viewHierarchy(excludeKeyboardElements: Boolean = false): ViewHierarchy = runInterruptible(Dispatchers.IO) {
        delegate.viewHierarchy(excludeKeyboardElements)
    }

    suspend fun findElementWithTimeout(
        timeoutMs: Long,
        filter: ElementFilter,
        viewHierarchy: ViewHierarchy? = null,
    ): FindElementResult? = runInterruptible(Dispatchers.IO) {
        delegate.findElementWithTimeout(timeoutMs, filter, viewHierarchy)
    }

    suspend fun findElementsByOnDeviceQuery(
        timeoutMs: Long,
        query: OnDeviceElementQuery,
    ): OnDeviceElementQueryResult? = runInterruptible(Dispatchers.IO) {
        delegate.findElementsByOnDeviceQuery(timeoutMs, query)
    }

    suspend fun allElementsMatching(filter: ElementFilter): List<TreeNode> = runInterruptible(Dispatchers.IO) {
        delegate.allElementsMatching(filter)
    }

    suspend fun waitForAppToSettle(
        initialHierarchy: ViewHierarchy? = null,
        appId: String? = null,
        waitToSettleTimeoutMs: Int? = null,
    ): ViewHierarchy? = runInterruptible(Dispatchers.IO) {
        delegate.waitForAppToSettle(initialHierarchy, appId, waitToSettleTimeoutMs)
    }

    suspend fun inputText(text: String) = runInterruptible(Dispatchers.IO) {
        delegate.inputText(text)
    }

    suspend fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) = runInterruptible(Dispatchers.IO) {
        delegate.openLink(link, appId, autoVerify, browser)
    }

    suspend fun addMedia(fileNames: List<String>) = runInterruptible(Dispatchers.IO) {
        delegate.addMedia(fileNames)
    }

    @Deprecated("Use takeScreenshot(Sink, Boolean) instead")
    suspend fun takeScreenshot(outFile: File, compressed: Boolean) = runInterruptible(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        delegate.takeScreenshot(outFile, compressed)
    }

    suspend fun takeScreenshot(sink: Sink, compressed: Boolean, bounds: Bounds? = null) = runInterruptible(Dispatchers.IO) {
        delegate.takeScreenshot(sink, compressed, bounds)
    }

    suspend fun startScreenRecording(out: Sink): ScreenRecording = runInterruptible(Dispatchers.IO) {
        delegate.startScreenRecording(out)
    }

    suspend fun setLocation(latitude: String, longitude: String) = runInterruptible(Dispatchers.IO) {
        delegate.setLocation(latitude, longitude)
    }

    suspend fun setOrientation(orientation: DeviceOrientation, waitForAppToSettle: Boolean = true) = runInterruptible(Dispatchers.IO) {
        delegate.setOrientation(orientation, waitForAppToSettle)
    }

    suspend fun eraseText(charactersToErase: Int) = runInterruptible(Dispatchers.IO) {
        delegate.eraseText(charactersToErase)
    }

    suspend fun waitForAnimationToEnd(timeout: Long?) = runInterruptible(Dispatchers.IO) {
        delegate.waitForAnimationToEnd(timeout)
    }

    suspend fun setProxy(host: String, port: Int) = runInterruptible(Dispatchers.IO) {
        delegate.setProxy(host, port)
    }

    suspend fun resetProxy() = runInterruptible(Dispatchers.IO) {
        delegate.resetProxy()
    }

    suspend fun isShutDown(): Boolean = runInterruptible(Dispatchers.IO) {
        delegate.isShutDown()
    }

    suspend fun isUnicodeInputSupported(): Boolean = runInterruptible(Dispatchers.IO) {
        delegate.isUnicodeInputSupported()
    }

    suspend fun isAirplaneModeEnabled(): Boolean = runInterruptible(Dispatchers.IO) {
        delegate.isAirplaneModeEnabled()
    }

    suspend fun setAirplaneModeState(enabled: Boolean) = runInterruptible(Dispatchers.IO) {
        delegate.setAirplaneModeState(enabled)
    }

    suspend fun setAndroidChromeDevToolsEnabled(enabled: Boolean) = runInterruptible(Dispatchers.IO) {
        delegate.setAndroidChromeDevToolsEnabled(enabled)
    }

    override fun close() = delegate.close()
}
