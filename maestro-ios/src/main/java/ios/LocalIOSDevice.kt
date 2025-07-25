package ios

import com.github.michaelbull.result.*
import device.IOSDevice
import device.IOSScreenRecording
import xcuitest.api.DeviceInfo
import ios.xctest.XCTestIOSDevice
import okio.Sink
import java.io.InputStream
import hierarchy.ViewHierarchy
import maestro.utils.Insight
import maestro.utils.Insights
import maestro.utils.NoopInsights
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LocalIOSDevice(
    override val deviceId: String?,
    private val xcTestDevice: XCTestIOSDevice,
    private val deviceController: IOSDevice,
    private val insights: Insights = NoopInsights
) : IOSDevice {

    private val executor by lazy { Executors.newSingleThreadScheduledExecutor() }

    override fun open() {
        xcTestDevice.open()
    }

    override fun deviceInfo(): DeviceInfo {
        return xcTestDevice.deviceInfo()
    }

    override fun viewHierarchy(excludeKeyboardElements: Boolean): ViewHierarchy {
        var isViewHierarchyInProgress = true
        val future = executor.schedule(
            {
                if (isViewHierarchyInProgress) {
                    insights.report(
                        Insight(
                            message = "Retrieving the hierarchy is taking longer than usual. This might be due to a " +
                                    "deep hierarchy in the current view. Please wait a bit more to complete the operation.",
                            level = Insight.Level.WARNING,
                        )
                    )
                }
            }, 15, TimeUnit.SECONDS
        )
        val result = xcTestDevice.viewHierarchy(excludeKeyboardElements)
        isViewHierarchyInProgress = false
        if (!future.isDone) {
            future.cancel(false)
        }
        return result
    }

    override fun tap(x: Int, y: Int) {
        return xcTestDevice.tap(x, y)
    }

    override fun longPress(x: Int, y: Int, durationMs: Long) {
        xcTestDevice.longPress(x, y, durationMs)
    }

    override fun pressKey(name: String) {
        xcTestDevice.pressKey(name)
    }

    override fun pressButton(name: String) {
        xcTestDevice.pressButton(name)
    }

    override fun scroll(
        xStart: Double,
        yStart: Double,
        xEnd: Double,
        yEnd: Double,
        duration: Double
    ) {
        xcTestDevice.scrollV2(xStart, yStart, xEnd, yEnd, duration)
    }

    override fun input(text: String) {
        xcTestDevice.input(text)
    }

    override fun install(stream: InputStream) {
        deviceController.install(stream)
    }

    override fun uninstall(id: String) {
        deviceController.uninstall(id)
    }

    override fun clearAppState(id: String) {
        deviceController.clearAppState(id)
    }

    override fun clearKeychain(): Result<Unit, Throwable> {
        return deviceController.clearKeychain()
    }

    override fun launch(
        id: String,
        launchArguments: Map<String, Any>,
    ) {
        deviceController.launch(id, launchArguments)
    }

    override fun stop(id: String) {
        xcTestDevice.stop(id)
    }

    override fun isKeyboardVisible(): Boolean {
        return xcTestDevice.isKeyboardVisible()
    }

    override fun openLink(link: String): Result<Unit, Throwable> {
        return deviceController.openLink(link)
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        xcTestDevice.takeScreenshot(out, compressed)
    }

    override fun startScreenRecording(out: Sink): Result<IOSScreenRecording, Throwable> {
        return deviceController.startScreenRecording(out)
    }

    override fun setLocation(latitude: Double, longitude: Double): Result<Unit, Throwable> {
        return deviceController.setLocation(latitude, longitude)
    }

    override fun setOrientation(orientation: String) {
        return xcTestDevice.setOrientation(orientation)
    }

    override fun isShutdown(): Boolean {
        return xcTestDevice.isShutdown()
    }

    override fun close() {
        xcTestDevice.close()
    }

    override fun isScreenStatic(): Boolean {
        return xcTestDevice.isScreenStatic()
    }

    override fun setPermissions(id: String, permissions: Map<String, String>) {
        deviceController.setPermissions(id, permissions)
        xcTestDevice.setPermissions(id, permissions)
    }

    override fun eraseText(charactersToErase: Int) {
        xcTestDevice.eraseText(charactersToErase)
    }

    override fun addMedia(path: String) {
        deviceController.addMedia(path)
    }
}
