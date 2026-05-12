package ios.xctest

import com.google.common.truth.Truth.assertThat
import ios.IOSDeviceErrors
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.installer.XCTestInstaller
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class XCTestIOSDeviceTest {

    @Test
    fun `transport unreachable from XCTestDriverClient is translated to IOSDeviceErrors Unreachable`() {
        val cause = SocketTimeoutException("simulated read timeout")
        val driverClient = XCTestDriverClient(
            installer = NoopInstaller,
            // The interceptor short-circuits before any socket use, so the port is never reached.
            client = XCTestClient("localhost", 1),
            okHttpClient = throwingOkHttpClient(cause),
        )
        val device = XCTestIOSDevice(
            deviceId = "test-device",
            client = driverClient,
            getInstalledApps = { emptySet() },
        )

        val thrown = assertThrows<IOSDeviceErrors.Unreachable> {
            device.tap(x = 10, y = 20)
        }

        assertThat(thrown.callName).isEqualTo("touch")
        assertThat(thrown.cause).isInstanceOf(maestro.utils.network.XCUITestServerError.Unreachable::class.java)
        assertThat(thrown.cause?.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `subsequent calls also throw Unreachable because the underlying latch short-circuits`() {
        val cause = SocketTimeoutException("simulated read timeout")
        val driverClient = XCTestDriverClient(
            installer = NoopInstaller,
            // The interceptor short-circuits before any socket use, so the port is never reached.
            client = XCTestClient("localhost", 1),
            okHttpClient = throwingOkHttpClient(cause),
        )
        val device = XCTestIOSDevice(
            deviceId = "test-device",
            client = driverClient,
            getInstalledApps = { emptySet() },
        )

        assertThrows<IOSDeviceErrors.Unreachable> { device.tap(10, 20) }
        // A second call must also surface as Unreachable — proves the translation arm catches
        // the cached Unreachable that the latch in XCTestDriverClient re-throws on every call.
        assertThrows<IOSDeviceErrors.Unreachable> { device.tap(30, 40) }
    }

    @Test
    fun `takeScreenshot honors callTimeoutMs when XCTest screenshot endpoint hangs server-side`() {
        // Bug today: XCTestIOSDevice.takeScreenshot does not propagate the user-supplied
        // timeoutMs to the underlying OkHttp Call. So when XCUITest's /screenshot endpoint
        // is slow to respond (server-side AX-snapshot stall during animation), the call
        // runs for the full server delay instead of bailing at the user's deadline.
        //
        // We exercise the real chain — XCTestIOSDevice -> XCTestDriverClient -> OkHttp —
        // against a real MockWebServer that delays the response headers. OkHttp's
        // Call.timeout() machinery only cancels real socket I/O, so the slowness has
        // to live on the wire (not in interceptor application code).

        val callTimeoutMs = 200L
        val serverHeadersDelayMs = 1500L

        val mockServer = MockWebServer()
        try {
            // Synthetic 1x1 PNG body — content irrelevant; this test is about wall-clock.
            val pngBytes = ByteArrayOutputStream().use { baos ->
                ImageIO.write(BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", baos)
                baos.toByteArray()
            }
            mockServer.enqueue(
                MockResponse()
                    .setHeadersDelay(serverHeadersDelayMs, TimeUnit.MILLISECONDS)
                    .setHeader("Content-Type", "image/png")
                    .setBody(Buffer().write(pngBytes))
            )
            mockServer.start()

            val driverClient = XCTestDriverClient(
                installer = NoopInstaller,
                client = XCTestClient(mockServer.hostName, mockServer.port),
            )
            val device = XCTestIOSDevice(
                deviceId = "test-device",
                client = driverClient,
                getInstalledApps = { emptySet() },
            )

            val start = System.currentTimeMillis()
            try {
                device.takeScreenshot(Buffer(), compressed = true, timeoutMs = callTimeoutMs)
            } catch (_: Exception) {
                // Expected once the fix is in: OkHttp's Call.timeout() throws
                // InterruptedIOException which XCTestDriverClient's transportCall translates
                // upward. Pre-fix the call returns normally; either path returns control.
            }
            val elapsedMs = System.currentTimeMillis() - start

            // Pre-fix: ~serverHeadersDelayMs (1500ms) — OkHttp's default 200s callTimeout
            // is the only thing bounding it. Fails today.
            // Post-fix: ~callTimeoutMs (~200-300ms) — Call.timeout() cancels at the deadline.
            assertThat(elapsedMs).isLessThan(callTimeoutMs + 500)
        } finally {
            mockServer.shutdown()
        }
    }

    private fun throwingOkHttpClient(cause: Throwable): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { _ -> throw cause }
            .build()

    private object NoopInstaller : XCTestInstaller {
        override fun start(): XCTestClient = error("not used in this test")
        override fun uninstall(): Boolean = error("not used in this test")
        override fun isChannelAlive(): Boolean = error("not used in this test")
        override fun close() {}
    }
}
