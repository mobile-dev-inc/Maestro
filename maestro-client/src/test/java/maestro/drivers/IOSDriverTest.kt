package maestro.drivers

import com.google.common.truth.Truth.assertThat
import device.IOSDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ios.IOSDeviceErrors
import ios.xctest.XCTestIOSDevice
import maestro.DeviceUnreachableException
import maestro.MaestroException
import maestro.utils.ScreenshotUtils
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xcuitest.XCTestClient
import xcuitest.XCTestDriverClient
import xcuitest.api.DeviceInfo
import xcuitest.installer.XCTestInstaller
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class IOSDriverTest {

    @Test
    fun `IOSDeviceErrors Unreachable from the device is translated to DeviceUnreachableException`() {
        val cause = SocketTimeoutException("Read timed out")
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws IOSDeviceErrors.Unreachable("deviceInfo", cause)

        val driver = IOSDriver(iosDevice)

        val thrown = assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        assertThat(thrown.callName).isEqualTo("deviceInfo")
        assertThat(thrown.cause).isInstanceOf(IOSDeviceErrors.Unreachable::class.java)
        assertThat(thrown.cause?.cause).isSameInstanceAs(cause)
    }

    @Test
    fun `IOSDriver does not cache - subsequent calls invoke the device again`() {
        // Fail-fast for the dead-runner case lives at the transport layer (XCTestDriverClient).
        // IOSDriver is now a thin translator: it converts each IOSDeviceErrors.Unreachable into
        // a DeviceUnreachableException without short-circuiting on its own. When the underlying
        // device keeps throwing (mimicking a still-tripped transport latch), the driver translates
        // each call independently.
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws IOSDeviceErrors.Unreachable("deviceInfo", SocketTimeoutException())

        val driver = IOSDriver(iosDevice)

        assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        verify(exactly = 2) { iosDevice.deviceInfo() }
    }

    @Test
    fun `non-transport exceptions still translate to their MaestroException counterparts`() {
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws IOSDeviceErrors.AppCrash("crashed")

        val driver = IOSDriver(iosDevice)

        assertThrows<MaestroException.AppCrash> { driver.deviceInfo() }
        assertThrows<MaestroException.AppCrash> { driver.deviceInfo() }
        verify(exactly = 2) { iosDevice.deviceInfo() }
    }

    @Test
    fun `successful calls pass through unchanged`() {
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } returns DeviceInfo(
            widthPixels = 1170,
            heightPixels = 2532,
            widthPoints = 390,
            heightPoints = 844,
        )

        val driver = IOSDriver(iosDevice)

        driver.deviceInfo()
        driver.deviceInfo()
        driver.deviceInfo()

        verify(exactly = 3) { iosDevice.deviceInfo() }
    }

    @Test
    fun `waitForAppToSettle respects user-supplied timeoutMs when device screen never settles`() {
        /**
         * Current commands like
         * - swipe:
         *     direction: LEFT
         *     waitToSettleTimeoutMs: 500
         * - tap:
         *     waitToSettleTimeoutMs: 500
         *
         * IOSDriver simply ignored the timeout here and waited always for 3 seconds breaking the behaviour.
         * This impacts all the element selector commands.
         *
         * This test just ensures that IOSDriver respects the user-supplied timeoutMs assuming the timeout is
         * respected internally by our driver logic.
         */
        val userTimeoutMs = 100
        val iosDevice = mockk<IOSDevice>(relaxed = true)

        // Simulate a device that never settles: tier-1 polls forever. The small sleep keeps
        // mockk from being invoked in a CPU-pegged tight loop (which can OOM the test JVM).
        every { iosDevice.isScreenStatic() } answers {
            Thread.sleep(20)
            false
        }

        val driver = IOSDriver(iosDevice)

        val startMs = System.currentTimeMillis()
        driver.waitForAppToSettle(initialHierarchy = null, appId = null, timeoutMs = userTimeoutMs)
        val elapsedMs = System.currentTimeMillis() - startMs

        // Contract: total wall-clock must be close to user's timeoutMs (with reasonable grace).
        // Today this fails with elapsedMs ~= 3000ms because of the hardcoded tier-1 cap.
        assertThat(elapsedMs).isLessThan(userTimeoutMs.toLong() + 500)
    }

    @Test
    fun `waitUntilScreenIsStatic respects user timeout when XCTest screenshot HTTP is slow`() {
        // Bug: ScreenshotUtils.waitUntilScreenIsStatic's loop body calls tryTakingScreenshot
        // which uses the timeout-less driver.takeScreenshot path. So when XCUITest's
        // /screenshot endpoint stalls server-side, each call blocks for the OkHttp default
        // (200s callTimeout), and the user-supplied timeoutMs is only checked between
        // iterations — never inside an in-flight HTTP call. One slow /screenshot can
        // therefore blow past the user's deadline by orders of magnitude.
        //
        // We exercise the real chain — ScreenshotUtils.waitUntilScreenIsStatic ->
        // tryTakingScreenshot -> driver.takeScreenshot (real IOSDriver) ->
        // iosDevice.takeScreenshot (real XCTestIOSDevice) -> XCTestDriverClient -> OkHttp —
        // against a real MockWebServer that delays response headers. OkHttp's Call.timeout()
        // only cancels real socket I/O, so the slowness has to live on the wire.

        val timeoutMs = 200L
        val perRequestDelayMs = 1500L

        val mockServer = MockWebServer()
        try {
            // Enqueue many varied PNGs so consecutive screenshots compare as different —
            // simulates a non-settling animation. Without varied content, the loop would
            // settle on the first iteration and the timing assertion becomes vacuous.
            repeat(8) { i ->
                val img = BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB)
                for (x in 0 until 20) for (y in 0 until 20) {
                    img.setRGB(x, y, ((i + 1) * 67890 + x * 31 + y) and 0xFFFFFF)
                }
                val baos = ByteArrayOutputStream()
                ImageIO.write(img, "png", baos)
                mockServer.enqueue(
                    MockResponse()
                        .setHeadersDelay(perRequestDelayMs, TimeUnit.MILLISECONDS)
                        .setHeader("Content-Type", "image/png")
                        .setBody(Buffer().write(baos.toByteArray()))
                )
            }
            mockServer.start()

            val driverClient = XCTestDriverClient(
                installer = NoopInstaller,
                client = XCTestClient(mockServer.hostName, mockServer.port),
            )
            val xcTestDevice = XCTestIOSDevice(
                deviceId = "test-device",
                client = driverClient,
                getInstalledApps = { emptySet() },
            )
            val driver = IOSDriver(xcTestDevice)

            val start = System.currentTimeMillis()
            ScreenshotUtils.waitUntilScreenIsStatic(
                timeoutMs = timeoutMs,
                threshold = 0.005,
                driver = driver,
            )
            val elapsedMs = System.currentTimeMillis() - start

            // Pre-fix: ~3000ms (two 1500ms hung calls run to completion before the
            //           MaestroTimer.retryUntilTrue between-iteration deadline check).
            // Post-fix: ~200-700ms (per-call HTTP timeout cancels in-flight calls).
            assertThat(elapsedMs).isLessThan(timeoutMs + 500)
        } finally {
            mockServer.shutdown()
        }
    }

    private object NoopInstaller : XCTestInstaller {
        override fun start(): XCTestClient = error("not used in this test")
        override fun uninstall(): Boolean = error("not used in this test")
        override fun isChannelAlive(): Boolean = error("not used in this test")
        override fun close() {}
    }
}
