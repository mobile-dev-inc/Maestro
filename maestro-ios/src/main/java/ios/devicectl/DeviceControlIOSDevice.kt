package ios.devicectl

import com.github.michaelbull.result.Result
import device.IOSDevice
import device.IOSScreenRecording
import hierarchy.ViewHierarchy
import okio.Sink
import org.slf4j.LoggerFactory
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
        TODO("Not yet implemented")
    }

    override fun deviceInfo(): DeviceInfo {
        TODO("Not yet implemented")
    }

    override fun viewHierarchy(excludeKeyboardElements: Boolean): ViewHierarchy {
        TODO("Not yet implemented")
    }

    override fun tap(x: Int, y: Int) {
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
        logger.warn(
            "clearState is not supported on real iOS devices due to iOS security restrictions. " +
            "App state will NOT be cleared. " +
            "Workarounds: (1) Remove clearState from tests, (2) Add app-level reset, " +
            "(3) Manually reinstall app between test runs"
        )
        // Do nothing - test continues without clearing state
    }

    override fun clearKeychain(): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun launch(id: String, launchArguments: Map<String, Any>) {
        TODO("Not yet implemented")
    }

    override fun stop(id: String) {
        error("not supported")
    }

    override fun isKeyboardVisible(): Boolean {
        TODO("Not yet implemented")
    }

    override fun openLink(link: String): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        TODO("Not yet implemented")
    }

    override fun startScreenRecording(out: Sink): Result<IOSScreenRecording, Throwable> {
        TODO("Not yet implemented")
    }

    override fun setLocation(latitude: Double, longitude: Double): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun setOrientation(orientation: String) {
        TODO("Not yet implemented")
    }

    override fun isShutdown(): Boolean {
        TODO("Not yet implemented")
    }

    override fun isScreenStatic(): Boolean {
        TODO("Not yet implemented")
    }

    override fun setPermissions(id: String, permissions: Map<String, String>) {
        /* noop */
    }

    override fun pressKey(name: String) {
        TODO("Not yet implemented")
    }

    override fun pressButton(name: String) {
        TODO("Not yet implemented")
    }

    override fun eraseText(charactersToErase: Int) {
        TODO("Not yet implemented")
    }

    override fun addMedia(path: String) {
        TODO("Not yet implemented")
    }

    override fun close() {
        // Skip uninstalling runner app on real devices to allow session reuse
        // logger.info("[Start] Uninstall the runner app")
        // uninstall(id = LocalXCTestInstaller.UI_TEST_RUNNER_APP_BUNDLE_ID)
        // logger.info("[Done] Uninstall the runner app")
    }
}