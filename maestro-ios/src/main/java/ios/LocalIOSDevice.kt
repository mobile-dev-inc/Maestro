package ios

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.getOrThrow
import com.github.michaelbull.result.recoverIf
import hierarchy.XCUIElement
import idb.Idb
import ios.device.DeviceInfo
import ios.xcrun.XCRunIOSDevice
import ios.xctest.XCTestIOSDevice
import okio.Sink
import java.io.File
import java.io.InputStream

class LocalIOSDevice(
    override val deviceId: String?,
    private val idbIOSDevice: IOSDevice,
    private val xcTestDevice: IOSDevice,
    private val xcRunIOSDevice: XCRunIOSDevice,
) : IOSDevice {

    override fun open() {
        idbIOSDevice.open()
        xcTestDevice.open()
    }

    override fun deviceInfo(): Result<DeviceInfo, Throwable> {
        return idbIOSDevice.deviceInfo()
    }

    override fun contentDescriptor(): Result<XCUIElement, Throwable> {
        return xcTestDevice.contentDescriptor()
            .recoverIf(
                { it is XCTestIOSDevice.IllegalArgumentSnapshotFailure },
                {
                    idbIOSDevice.contentDescriptor()
                        .getOrThrow()
                }
            )
    }

    override fun tap(x: Int, y: Int): Result<Unit, Throwable> {
        return idbIOSDevice.tap(x, y)
    }

    override fun longPress(x: Int, y: Int): Result<Unit, Throwable> {
        return idbIOSDevice.longPress(x, y)
    }

    override fun pressKey(code: Int): Result<Unit, Throwable> {
        return idbIOSDevice.pressKey(code)
    }

    override fun pressButton(code: Int): Result<Unit, Throwable> {
        return idbIOSDevice.pressButton(code)
    }

    override fun scroll(
        xStart: Float,
        yStart: Float,
        xEnd: Float,
        yEnd: Float,
        velocity: Float?
    ): Result<Unit, Throwable> {
        return xcTestDevice.scroll(xStart, yStart, xEnd, yEnd, velocity)
    }

    override fun input(text: String): Result<Unit, Throwable> {
        return xcTestDevice.input(text)
            .recoverIf(
                { it is XCTestIOSDevice.InputFieldNotFound },
                {
                    idbIOSDevice.input(text)
                        .getOrThrow()
                }
            )
    }

    override fun install(stream: InputStream): Result<Unit, Throwable> {
        return idbIOSDevice.install(stream)
    }

    override fun uninstall(id: String): Result<Unit, Throwable> {
        return idbIOSDevice.uninstall(id)
    }

    override fun pullAppState(id: String, file: File): Result<Idb.PullResponse, Throwable> {
        return idbIOSDevice.pullAppState(id, file)
    }

    override fun pushAppState(id: String, file: File): Result<Unit, Throwable> {
        return idbIOSDevice.pushAppState(id, file)
    }

    override fun clearAppState(id: String): Result<Idb.RmResponse, Throwable> {
        return idbIOSDevice.clearAppState(id)
    }

    override fun clearKeychain(): Result<Unit, Throwable> {
        return idbIOSDevice.clearKeychain()
    }

    override fun launch(id: String): Result<Unit, Throwable> {
        return idbIOSDevice.launch(id)
    }

    override fun stop(id: String): Result<Unit, Throwable> {
        return idbIOSDevice.stop(id)
    }

    override fun openLink(link: String): Result<Unit, Throwable> {
        return idbIOSDevice.openLink(link)
    }

    override fun takeScreenshot(out: Sink): Result<Unit, Throwable> {
        return xcRunIOSDevice.takeScreenshot(out)
    }

    override fun startScreenRecording(out: Sink): Result<IOSScreenRecording, Throwable> {
        return idbIOSDevice.startScreenRecording(out)
    }

    override fun setLocation(latitude: Double, longitude: Double): Result<Unit, Throwable> {
        return idbIOSDevice.setLocation(latitude, longitude)
    }

    override fun isShutdown(): Boolean {
        return idbIOSDevice.isShutdown() && xcTestDevice.isShutdown()
    }

    override fun close() {
        idbIOSDevice.close()
        xcTestDevice.close()
    }

}