package ios.devicectl

import com.github.michaelbull.result.Result
import hierarchy.ViewHierarchy
import ios.IOSDevice
import ios.IOSScreenRecording
import okio.Sink
import xcuitest.api.DeviceInfo
import java.io.InputStream

class DeviceControlIOSDevice(override val deviceId: String) : IOSDevice {

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

    override fun uninstall(id: String): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun clearAppState(id: String) {
        TODO("Not yet implemented")
    }

    override fun clearKeychain(): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun launch(id: String, launchArguments: Map<String, Any>) {
        TODO("Not yet implemented")
    }

    override fun stop(id: String) {
        TODO("Not yet implemented")
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
        /* noop */
    }
}