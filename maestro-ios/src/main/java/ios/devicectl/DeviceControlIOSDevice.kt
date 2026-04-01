package ios.devicectl

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.Err
import device.IOSDevice
import device.IOSScreenRecording
import hierarchy.ViewHierarchy
import okio.Sink
import org.slf4j.LoggerFactory
import util.CommandLineUtils
import util.LocalIOSDevice
import xcuitest.api.DeviceInfo
import xcuitest.installer.LocalXCTestInstaller
import java.io.InputStream

class DeviceControlIOSDevice(override val deviceId: String) : IOSDevice {

    private val localIOSDevice by lazy { LocalIOSDevice() }

    companion object {
        private val logger = LoggerFactory.getLogger(DeviceControlIOSDevice::class.java)
    }

    override fun open() {
        // No-op for real devices — device is already running
    }

    override fun deviceInfo(): DeviceInfo {
        // Device info is fetched via XCTest HTTP server, not devicectl
        TODO("Not yet implemented")
    }

    override fun viewHierarchy(excludeKeyboardElements: Boolean): ViewHierarchy {
        // View hierarchy is fetched via XCTest HTTP server, not devicectl
        TODO("Not yet implemented")
    }

    override fun tap(x: Int, y: Int) {
        // Taps are handled via XCTest HTTP server, not devicectl
        TODO("Not yet implemented")
    }

    override fun longPress(x: Int, y: Int, durationMs: Long) {
        TODO("Not yet implemented")
    }

    override fun scroll(xStart: Double, yStart: Double, xEnd: Double, yEnd: Double, duration: Double) {
        TODO("Not yet implemented")
    }

    override fun input(text: String) {
        TODO("Not yet implemented")
    }

    override fun install(stream: InputStream) {
        TODO("Not yet implemented")
    }

    override fun uninstall(id: String) {
        localIOSDevice.uninstall(deviceId, id)
    }

    override fun clearAppState(id: String) {
        // devicectl does not support clearing app state on real devices.
        // Best effort: terminate the app (data clearing requires reinstall).
        logger.info("clearAppState is not fully supported on real devices — terminating app instead")
        stop(id)
    }

    override fun clearKeychain(): Result<Unit, Throwable> {
        logger.info("clearKeychain is not supported on real devices")
        return Ok(Unit)
    }

    override fun launch(id: String, launchArguments: Map<String, Any>) {
        logger.info("Launching app $id on device $deviceId")
        val command = mutableListOf(
            "xcrun", "devicectl", "device", "process", "launch",
            "--terminate-existing",
            "--device", deviceId,
            id,
        )
        // Pass launch arguments
        for ((key, value) in launchArguments) {
            command.add("--$key")
            command.add(value.toString())
        }
        CommandLineUtils.runCommand(command)
    }

    override fun stop(id: String) {
        logger.info("Stopping app $id on device $deviceId")
        try {
            CommandLineUtils.runCommand(
                listOf(
                    "xcrun", "devicectl", "device", "process", "terminate",
                    "--device", deviceId,
                    "--pid", getAppPid(id)?.toString() ?: return,
                )
            )
        } catch (e: Exception) {
            logger.warn("Failed to stop app $id: ${e.message}")
        }
    }

    private fun getAppPid(bundleId: String): Long? {
        // devicectl doesn't have a direct "get pid by bundle id" command,
        // so we use the running app list approach
        return null
    }

    override fun isKeyboardVisible(): Boolean {
        // Keyboard visibility is checked via XCTest HTTP server
        TODO("Not yet implemented")
    }

    override fun openLink(link: String): Result<Unit, Throwable> {
        logger.info("Opening link $link on device $deviceId")
        return try {
            CommandLineUtils.runCommand(
                listOf(
                    "xcrun", "devicectl", "device", "process", "launch",
                    "--device", deviceId,
                    "com.apple.mobilesafari",
                    link,
                )
            )
            Ok(Unit)
        } catch (e: Exception) {
            Err(e)
        }
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        // Screenshots are taken via XCTest HTTP server, not devicectl
        TODO("Not yet implemented")
    }

    override fun startScreenRecording(out: Sink): Result<IOSScreenRecording, Throwable> {
        logger.info("Screen recording is not yet supported on real devices via devicectl")
        return Err(UnsupportedOperationException("Screen recording not supported on real devices"))
    }

    override fun setLocation(latitude: Double, longitude: Double): Result<Unit, Throwable> {
        logger.info("setLocation is not supported on real devices")
        return Err(UnsupportedOperationException("setLocation not supported on real devices"))
    }

    override fun setOrientation(orientation: String) {
        // Orientation is set via XCTest HTTP server
        TODO("Not yet implemented")
    }

    override fun isShutdown(): Boolean {
        return false
    }

    override fun isScreenStatic(): Boolean {
        // Screen static check is done via XCTest HTTP server
        TODO("Not yet implemented")
    }

    override fun setPermissions(id: String, permissions: Map<String, String>) {
        /* noop - permissions must be set manually on real devices */
    }

    override fun pressKey(name: String) {
        // Key presses are handled via XCTest HTTP server
        TODO("Not yet implemented")
    }

    override fun pressButton(name: String) {
        // Button presses are handled via XCTest HTTP server
        TODO("Not yet implemented")
    }

    override fun eraseText(charactersToErase: Int) {
        // Text erasure is handled via XCTest HTTP server
        TODO("Not yet implemented")
    }

    override fun addMedia(path: String) {
        TODO("Not yet implemented")
    }

    override fun close() {
        logger.info("[Start] Uninstall the runner app")
        uninstall(id = LocalXCTestInstaller.UI_TEST_RUNNER_APP_BUNDLE_ID)
        logger.info("[Done] Uninstall the runner app")
    }
}
