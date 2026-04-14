package maestro.drivers

import maestro.Capability
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.OnDeviceElementQuery
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.DeviceOrientation
import maestro.device.Platform
import maestro.drivers.roku.RokuAppUIParser
import maestro.drivers.roku.RokuKeyMapping
import maestro.roku.RokuEcpClient
import okio.Sink
import okio.buffer
import org.slf4j.LoggerFactory
import java.io.File

/**
 * Maestro Driver implementation for Roku devices.
 *
 * Communicates with Roku devices over the network via the External Control Protocol (ECP).
 * Roku uses D-pad focus navigation rather than touch input.
 *
 * Reference: roku-test-automation project for ECP patterns.
 */
class RokuDriver(
    private val host: String,
    private val password: String = System.getenv("MAESTRO_ROKU_PASSWORD") ?: "",
    private val keypressDelayMs: Long = 100,
) : Driver {

    private val logger = LoggerFactory.getLogger(RokuDriver::class.java)

    private lateinit var ecpClient: RokuEcpClient
    private var deviceInfo: RokuEcpClient.RokuDeviceInfo? = null
    private var shutdown = false

    override fun name(): String = "Roku"

    override fun open() {
        ecpClient = RokuEcpClient(
            host = host,
            password = password,
            keypressDelayMs = keypressDelayMs,
        )

        if (!ecpClient.isReachable()) {
            throw IllegalStateException(
                "Cannot connect to Roku device at $host. " +
                "Ensure the device is on the same network and developer mode is enabled."
            )
        }

        deviceInfo = ecpClient.getDeviceInfo()
        logger.info("Connected to Roku device: ${deviceInfo?.friendlyName} (${deviceInfo?.modelName})")
        shutdown = false
    }

    override fun close() {
        if (::ecpClient.isInitialized) {
            ecpClient.close()
        }
        shutdown = true
    }

    override fun deviceInfo(): DeviceInfo {
        val info = deviceInfo ?: ecpClient.getDeviceInfo()
            ?: throw IllegalStateException("Failed to get Roku device info")

        return DeviceInfo(
            platform = Platform.ROKU,
            widthPixels = info.widthPixels,
            heightPixels = info.heightPixels,
            widthGrid = info.widthPixels,
            heightGrid = info.heightPixels,
        )
    }

    override fun launchApp(appId: String, launchArguments: Map<String, Any>) {
        val stringParams = launchArguments.mapValues { it.value.toString() }
        ecpClient.launchChannel(appId, stringParams)

        // Wait for app to become active
        val startTime = System.currentTimeMillis()
        val timeout = 10000L
        var appActive = false
        while (System.currentTimeMillis() - startTime < timeout) {
            if (ecpClient.isActiveApp(appId)) {
                appActive = true
                break
            }
            Thread.sleep(200)
        }
        if (!appActive) {
            logger.warn("App $appId did not become active within timeout")
            return
        }

        // Wait for app UI to render (SceneGraph screen must be present with child nodes)
        while (System.currentTimeMillis() - startTime < timeout) {
            val doc = ecpClient.getAppUI()
            if (doc != null) {
                val root = doc.documentElement
                val topscreen = root?.getElementsByTagName("screen")
                if (topscreen != null && topscreen.length > 0) {
                    val screen = topscreen.item(0) as? org.w3c.dom.Element
                    if (screen != null && screen.childNodes.length > 0) {
                        logger.info("App $appId UI is ready")
                        return
                    }
                }
            }
            Thread.sleep(500)
        }
        logger.warn("App $appId launched but UI may not be fully rendered")
    }

    override fun stopApp(appId: String) {
        // Roku has no direct "stop app" API — press Home to return to launcher
        ecpClient.sendKeypress("Home")
    }

    override fun killApp(appId: String) {
        // Same as stopApp for Roku
        stopApp(appId)
    }

    override fun clearAppState(appId: String) {
        // Roku has no direct "clear state" API
        logger.warn("clearAppState is not supported on Roku. Consider reinstalling the channel.")
    }

    override fun clearKeychain() {
        // Not applicable on Roku
    }

    override fun tap(point: Point) {
        // Roku does not support coordinate-based tap.
        // Send "Select" which activates the currently focused element.
        logger.debug("tap() called on Roku — sending Select keypress (Roku is D-pad based)")
        ecpClient.sendKeypress("Select")
    }

    override fun longPress(point: Point) {
        // Roku does not support touch-based long press.
        // Some Roku apps respond to a held Select key.
        logger.debug("longPress() on Roku — holding Select key for 1 second")
        ecpClient.sendKeyDown("Select")
        Thread.sleep(1000)
        ecpClient.sendKeyUp("Select")
    }

    override fun pressKey(code: KeyCode) {
        val ecpKey = RokuKeyMapping.toEcpKey(code)
            ?: throw UnsupportedOperationException("KeyCode $code is not supported on Roku")
        ecpClient.sendKeypress(ecpKey)
    }

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
        val document = ecpClient.getAppUI()
        if (document == null) {
            logger.warn(
                "Failed to get app UI from Roku device at $host. " +
                "View hierarchy will be empty — assertVisible and other element lookups will fail. " +
                "Check ECP network access setting (Settings > System > Advanced > Control by mobile apps > Permissive)."
            )
            return TreeNode(
                attributes = mutableMapOf(),
                children = emptyList(),
                clickable = false,
                enabled = true,
                focused = false,
                checked = null,
                selected = null,
            )
        }

        return RokuAppUIParser.parse(document)
    }

    override fun scrollVertical() {
        // Simulate scroll by pressing Down multiple times
        repeat(5) {
            ecpClient.sendKeypress("Down")
        }
    }

    override fun isKeyboardVisible(): Boolean {
        // Heuristic: check if the view hierarchy contains a keyboard-like node
        // Roku's on-screen keyboard typically shows as a specific component type
        return false
    }

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        // Translate swipe direction into D-pad presses
        val dx = end.x - start.x
        val dy = end.y - start.y
        val presses = 5 // Number of key presses to simulate a swipe

        val key = when {
            Math.abs(dy) > Math.abs(dx) && dy < 0 -> "Up"
            Math.abs(dy) > Math.abs(dx) && dy >= 0 -> "Down"
            dx < 0 -> "Left"
            else -> "Right"
        }

        repeat(presses) {
            ecpClient.sendKeypress(key)
        }
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        val key = when (swipeDirection) {
            SwipeDirection.UP -> "Up"
            SwipeDirection.DOWN -> "Down"
            SwipeDirection.LEFT -> "Left"
            SwipeDirection.RIGHT -> "Right"
        }
        repeat(5) {
            ecpClient.sendKeypress(key)
        }
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        swipe(direction, durationMs)
    }

    override fun backPress() {
        ecpClient.sendKeypress("Back")
    }

    override fun inputText(text: String) {
        ecpClient.sendText(text)
    }

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        // Roku has limited deep linking support via launch params
        if (appId != null) {
            ecpClient.launchChannel(appId, mapOf("contentId" to link))
        } else {
            logger.warn("openLink without appId is not supported on Roku")
        }
    }

    override fun hideKeyboard() {
        ecpClient.sendKeypress("Back")
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        ecpClient.takeScreenshot(out)
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        throw UnsupportedOperationException("Screen recording is not supported on Roku devices")
    }

    override fun setLocation(latitude: Double, longitude: Double) {
        // Not supported on Roku
    }

    override fun setOrientation(orientation: DeviceOrientation) {
        // TVs don't rotate
    }

    override fun eraseText(charactersToErase: Int) {
        repeat(charactersToErase) {
            ecpClient.sendKeypress("Backspace")
        }
    }

    override fun setProxy(host: String, port: Int) {
        // Not supported on Roku
    }

    override fun resetProxy() {
        // Not supported on Roku
    }

    override fun isShutdown(): Boolean = shutdown

    override fun isUnicodeInputSupported(): Boolean = false

    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        // Poll /query/app-ui and compare consecutive responses.
        // When the XML stops changing, the screen is static.
        val pollIntervalMs = 300L
        val startTime = System.currentTimeMillis()
        var previousXml: String? = null

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            val currentXml = ecpClient.getAppUIRaw()
            if (currentXml != null && currentXml == previousXml) {
                return true
            }
            previousXml = currentXml
            Thread.sleep(pollIntervalMs)
        }
        logger.debug("Screen did not become static within ${timeoutMs}ms")
        return false
    }

    override fun waitForAppToSettle(
        initialHierarchy: ViewHierarchy?,
        appId: String?,
        timeoutMs: Int?,
    ): ViewHierarchy? {
        val timeout = timeoutMs?.toLong() ?: 3000L
        waitUntilScreenIsStatic(minOf(timeout, 3000))
        return null
    }

    override fun capabilities(): List<Capability> = emptyList()

    override fun setPermissions(appId: String, permissions: Map<String, String>) {
        // Not applicable on Roku
    }

    override fun addMedia(mediaFiles: List<File>) {
        // Not supported on Roku
    }

    override fun isAirplaneModeEnabled(): Boolean = false

    override fun setAirplaneMode(enabled: Boolean) {
        // Not supported on Roku
    }
}
