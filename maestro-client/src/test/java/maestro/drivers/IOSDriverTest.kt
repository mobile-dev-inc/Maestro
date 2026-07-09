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
import util.IOSDeviceType
import xcuitest.api.DeviceInfo
import java.net.SocketTimeoutException

class IOSDriverTest {

    @Test
    fun `real device driver reports a physical device name`() {
        val iosDevice = mockk<IOSDevice>(relaxed = true)

        val driver = IOSDriver(iosDevice = iosDevice, deviceType = IOSDeviceType.REAL)

        assertThat(driver.name()).isEqualTo(IOSDriver.REAL_DEVICE_NAME)
    }

    @Test
    fun `real device proxy commands fail instead of changing the Mac network service`() {
        val driver = IOSDriver(
            iosDevice = mockk(relaxed = true),
            deviceType = IOSDeviceType.REAL,
        )

        val setError = assertThrows<UnsupportedOperationException> {
            driver.setProxy("127.0.0.1", 8080)
        }
        val resetError = assertThrows<UnsupportedOperationException> {
            driver.resetProxy()
        }

        assertThat(setError).hasMessageThat().contains("not supported on physical iOS devices")
        assertThat(resetError).hasMessageThat().contains("not supported on physical iOS devices")
    }

    @Test
    fun `IOSDeviceErrors Unreachable from the device is translated to DeviceUnreachableException`() {
        val cause = SocketTimeoutException("Read timed out")
        val iosDevice = mockk<IOSDevice>(relaxed = true)
        every { iosDevice.deviceInfo() } throws IOSDeviceErrors.Unreachable("deviceInfo", cause)

        val driver = IOSDriver(iosDevice)

        val thrown = assertThrows<DeviceUnreachableException> { driver.deviceInfo() }
        assertThat(thrown.operation).isEqualTo("deviceInfo")
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
