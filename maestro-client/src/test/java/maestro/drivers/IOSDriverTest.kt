package maestro.drivers

import com.google.common.truth.Truth.assertThat
import device.IOSDevice
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import ios.IOSDeviceErrors
import maestro.DeviceUnreachableException
import maestro.MaestroException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import xcuitest.api.DeviceInfo
import java.net.SocketTimeoutException

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
}
