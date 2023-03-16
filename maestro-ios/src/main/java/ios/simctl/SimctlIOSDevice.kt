package ios.simctl

import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.runCatching
import hierarchy.XCUIElement
import ios.IOSDevice
import ios.IOSScreenRecording
import ios.device.DeviceInfo
import okio.Sink
import java.io.File
import java.io.InputStream

class SimctlIOSDevice(
    override val deviceId: String,
) : IOSDevice {
    override fun open() {
        TODO("Not yet implemented")
    }

    override fun deviceInfo(): Result<DeviceInfo, Throwable> {
        TODO("Not yet implemented")
    }

    override fun contentDescriptor(): Result<XCUIElement, Throwable> {
        TODO("Not yet implemented")
    }

    override fun tap(x: Int, y: Int): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun longPress(x: Int, y: Int, durationMs: Long): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun pressKey(code: Int): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun pressButton(code: Int): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun scroll(xStart: Double, yStart: Double, xEnd: Double, yEnd: Double, duration: Double): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun input(text: String): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun install(stream: InputStream): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun uninstall(id: String): Result<Unit, Throwable> {
        return runCatching {
            Simctl.uninstall(deviceId, id)
        }
    }

    override fun pullAppState(id: String, file: File): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun pushAppState(id: String, file: File): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun clearAppState(id: String): Result<Unit, Throwable> {
        Simctl.clearAppState(deviceId, id)
        return Ok(Unit)
    }

    override fun clearKeychain(): Result<Unit, Throwable> {
        return runCatching {
            Simctl.clearKeychain(deviceId)
        }
    }

    override fun launch(id: String): Result<Unit, Throwable> {
        return runCatching {
            Simctl.launch(deviceId, id)
        }
    }

    override fun stop(id: String): Result<Unit, Throwable> {
        return runCatching {
            Simctl.terminate(deviceId, id)
        }
    }

    override fun openLink(link: String): Result<Unit, Throwable> {
        return runCatching {
            Simctl.openURL(deviceId, link)
        }
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean): Result<Unit, Throwable> {
        TODO("Not yet implemented")
    }

    override fun startScreenRecording(out: Sink): Result<IOSScreenRecording, Throwable> {
        TODO("Not yet implemented")
    }

    override fun setLocation(latitude: Double, longitude: Double): Result<Unit, Throwable> {
        return runCatching {
            Simctl.setLocation(deviceId, latitude, longitude)
        }
    }

    override fun isShutdown(): Boolean {
        TODO("Not yet implemented")
    }

    override fun isScreenStatic(): Result<Boolean, Throwable> {
        TODO("Not yet implemented")
    }

    override fun setPermissions(id: String, permissions: Map<String, String>) {
        Simctl.setPermissions(deviceId, id, permissions)
    }

    override fun close() {
        TODO("Not yet implemented")
    }

}
