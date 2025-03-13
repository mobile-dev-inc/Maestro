package ios.xctest

import com.github.michaelbull.result.Result
import hierarchy.ViewHierarchy
import ios.IOSDevice
import ios.IOSDeviceErrors
import ios.IOSScreenRecording
import xcuitest.api.DeviceInfo
import maestro.utils.DepthTracker
import maestro.utils.network.XCUITestServerError
import okio.Sink
import okio.buffer
import org.slf4j.LoggerFactory
import xcuitest.XCTestDriverClient
import java.io.InputStream
import java.net.SocketTimeoutException
import java.util.UUID

class XCTestIOSDevice(
    override val deviceId: String?,
    private val client: XCTestDriverClient,
    private val getInstalledApps: () -> Set<String>,
) : IOSDevice {
    private val logger = LoggerFactory.getLogger(XCTestIOSDevice::class.java)

    override fun open() {
        logger.trace("Opening a connection")
        client.restartXCTestRunner()
    }

    override fun deviceInfo(): DeviceInfo {
        return execute {
            val deviceInfo = client.deviceInfo()
            logger.info("Device info $deviceInfo")
            deviceInfo
        }
    }

    override fun viewHierarchy(excludeKeyboardElements: Boolean): ViewHierarchy {
        return execute {
            // TODO(as): remove this list of apps from here once tested on cloud, we are not using this appIds now on server.
            val viewHierarchy = client.viewHierarchy(installedApps = emptySet(), excludeKeyboardElements)
            DepthTracker.trackDepth(viewHierarchy.depth)
            logger.trace("Depth received: ${viewHierarchy.depth}")
            viewHierarchy
        }
    }

    override fun tap(x: Int, y: Int) {
        execute {
            client.tap(
                x = x.toFloat(),
                y = y.toFloat(),
            )
        }
    }

    override fun longPress(x: Int, y: Int, durationMs: Long) {
        execute {
            client.tap(
                x = x.toFloat(),
                y = y.toFloat(),
                duration = durationMs.toDouble() / 1000
            )
        }
    }

    override fun pressKey(name: String) {
        execute { client.pressKey(name) }
    }

    override fun pressButton(name: String) {
        execute { client.pressButton(name) }
    }

    override fun addMedia(path: String) {
        error("Not supported")
    }

    override fun scroll(
        xStart: Double,
        yStart: Double,
        xEnd: Double,
        yEnd: Double,
        duration: Double,
    ) {
        execute {
            client.swipe(
                appId = activeAppId(),
                startX = xStart,
                startY = yStart,
                endX = xEnd,
                endY = yEnd,
                duration = duration
            )
        }
    }

    fun scrollV2(
        xStart: Double,
        yStart: Double,
        xEnd: Double,
        yEnd: Double,
        duration: Double,
    ) {
        execute {
            // TODO(as): remove this list of apps from here once tested on cloud, we are not using this appIds now on server.
            client.swipeV2(
                installedApps = emptySet(),
                startX = xStart,
                startY = yStart,
                endX = xEnd,
                endY = yEnd,
                duration = duration,
            )
        }
    }

    override fun input(text: String) {
       execute {
           // TODO(as): remove this list of apps from here once tested on cloud, we are not using this appIds now on server.
           client.inputText(
               text = text,
               appIds = emptySet(),
           )
       }
    }

    override fun install(stream: InputStream) {
        error("Not supported")
    }

    override fun uninstall(id: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun clearAppState(id: String) {
        error("Not supported")
    }

    override fun clearKeychain(): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun launch(
        id: String,
        launchArguments: Map<String, Any>,
        maestroSessionId: UUID?,
    ): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun stop(id: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun isKeyboardVisible(): Boolean {
        val appIds = getInstalledApps()
        return execute { client.keyboardInfo(appIds).isKeyboardVisible }
    }

    override fun openLink(link: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        execute {
            val bytes = client.screenshot(compressed)
            out.buffer().use { it.write(bytes) }
        }
    }

    override fun startScreenRecording(out: Sink): Result<IOSScreenRecording, Throwable> {
        error("Not supported")
    }

    override fun setLocation(latitude: Double, longitude: Double): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun isShutdown(): Boolean {
        return !client.isChannelAlive()
    }

    override fun close() {
        client.close()
    }

    override fun isScreenStatic(): Boolean {
        return execute {
            val isScreenStatic = client.isScreenStatic().isScreenStatic
            logger.info("Screen diff request finished with isScreenStatic = $isScreenStatic")
            isScreenStatic
        }
    }

    override fun setPermissions(id: String, permissions: Map<String, String>) {
        val mutable = permissions.toMutableMap()
        if (mutable.containsKey("all")) {
            val value = mutable.remove("all")
            allPermissions.forEach {
                when (value) {
                    "allow" -> mutable.putIfAbsent(it, "allow")
                    "deny" -> mutable.putIfAbsent(it, "deny")
                    "unset" -> mutable.putIfAbsent(it, "unset")
                    else -> throw IllegalArgumentException("Permission 'all' can be set to 'allow', 'deny' or 'unset', not '$value'")
                }
            }
        }

        execute { client.setPermissions(mutable) }
    }

    override fun eraseText(charactersToErase: Int) {
        // TODO(as): remove this list of apps from here once tested on cloud, we are not using this appIds now on server.
        execute { client.eraseText(charactersToErase, appIds = emptySet()) }
    }

    private fun activeAppId(): String {
        return execute {
            val appIds = getInstalledApps()
            logger.info("installed apps: $appIds")

            client.runningAppId(appIds).runningAppBundleId
        }
    }

    private fun <T> execute(call: () -> T): T {
        return try {
            call()
        } catch (appCrashException: XCUITestServerError.AppCrash) {
            throw IOSDeviceErrors.AppCrash(
                "App crashed or stopped while executing flow, please check diagnostic logs: " +
                        "~/Library/Logs/DiagnosticReports directory"
            )
        }
    }

    companion object {
        private val allPermissions = listOf(
            "notifications"
        )
    }

}
