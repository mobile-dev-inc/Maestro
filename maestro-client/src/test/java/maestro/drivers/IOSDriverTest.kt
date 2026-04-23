package maestro.drivers

import com.google.common.truth.Truth.assertThat
import device.IOSDevice
import io.mockk.clearMocks
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
    fun `socket timeout trips the driver and short-circuits subsequent calls without re-invoking the device`() {
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws SocketTimeoutException("Read timed out")

        val driver = IOSDriver(iosDevice)

        // First call: real call, hits the device, gets timeout, wraps to DeviceUnreachableException
        val first = assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        assertThat(first.callName).isEqualTo("deviceInfo")
        assertThat(first.cause).isInstanceOf(SocketTimeoutException::class.java)
        verify(exactly = 1) { iosDevice.deviceInfo() }

        // Second call: trip flag short-circuits, device is NOT invoked again
        val second = assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        assertThat(second).isSameInstanceAs(first)
        verify(exactly = 1) { iosDevice.deviceInfo() }

        // A different driver method also short-circuits without invoking the device
        assertThrows<DeviceUnreachableException> { driver.isKeyboardVisible() }
        verify(exactly = 0) { iosDevice.isKeyboardVisible() }
    }

    @Test
    fun `non-transport exceptions do not trip the driver`() {
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws IOSDeviceErrors.AppCrash("crashed")

        val driver = IOSDriver(iosDevice)

        // First call: AppCrash maps to MaestroException.AppCrash, NOT DeviceUnreachableException
        assertThrows<MaestroException.AppCrash> { driver.deviceInfo() }

        // Second call: trip flag is NOT set, the device is invoked again
        assertThrows<MaestroException.AppCrash> { driver.deviceInfo() }
        verify(exactly = 2) { iosDevice.deviceInfo() }
    }

    @Test
    fun `successful calls do not trip the driver`() {
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
