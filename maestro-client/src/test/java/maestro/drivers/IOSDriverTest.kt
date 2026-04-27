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
}
