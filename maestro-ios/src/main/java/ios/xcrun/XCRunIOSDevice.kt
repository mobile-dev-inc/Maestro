package ios.xcrun

import com.github.michaelbull.result.Result
import com.github.michaelbull.result.runCatching
import hierarchy.XCUIElement
import idb.Idb
import ios.IOSDevice
import ios.IOSScreenRecording
import ios.device.DeviceInfo
import okio.Sink
import okio.buffer
import okio.source
import util.XCRunnerSimctl
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.pathString

class XCRunIOSDevice(
    override val deviceId: String?
) : IOSDevice {

    private var closed: Boolean = false

    override fun open() {
        // Do nothing
    }

    override fun deviceInfo(): Result<DeviceInfo, Throwable> {
        error("Not supported")
    }

    override fun contentDescriptor(): Result<XCUIElement, Throwable> {
        error("Not supported")
    }

    override fun tap(x: Int, y: Int): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun longPress(x: Int, y: Int): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun pressKey(code: Int): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun pressButton(code: Int): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun scroll(
        xStart: Float,
        yStart: Float,
        xEnd: Float,
        yEnd: Float,
        velocity: Float?
    ): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun input(text: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun install(stream: InputStream): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun uninstall(id: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun pullAppState(id: String, file: File): Result<Idb.PullResponse, Throwable> {
        error("Not supported")
    }

    override fun pushAppState(id: String, file: File): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun clearAppState(id: String): Result<Idb.RmResponse, Throwable> {
        error("Not supported")
    }

    override fun clearKeychain(): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun launch(id: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun stop(id: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun openLink(link: String): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun takeScreenshot(out: Sink): Result<Unit, Throwable> {
        return runCatching {
            val tmpImage = Files.createTempFile("tmp_image", ".png")
            XCRunnerSimctl.screenshot(tmpImage.toAbsolutePath().pathString)
            out
                .buffer()
                .use {
                    it.write(tmpImage.source().buffer().readByteArray())
                }
            tmpImage.deleteIfExists()
        }
    }

    override fun startScreenRecording(out: Sink): Result<IOSScreenRecording, Throwable> {
        error("Not supported")
    }

    override fun setLocation(latitude: Double, longitude: Double): Result<Unit, Throwable> {
        error("Not supported")
    }

    override fun isShutdown(): Boolean {
        return closed
    }

    override fun close() {
        closed = true
    }

}