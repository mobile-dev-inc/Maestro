package ios

import device.IOSDevice
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import ios.xctest.XCTestIOSDevice
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LocalIOSDeviceTest {

    @Test
    fun `stop falls back to the platform controller when XCTest termination fails`() {
        val xcTestDevice = mockk<XCTestIOSDevice>()
        val deviceController = mockk<IOSDevice>()
        every { xcTestDevice.stop(APP_ID) } throws IOSDeviceErrors.Unreachable(
            "terminateApp",
            IllegalStateException("runner unavailable"),
        )
        justRun { deviceController.stop(APP_ID) }
        val device = LocalIOSDevice(DEVICE_ID, xcTestDevice, deviceController)

        device.stop(APP_ID)

        verify(exactly = 1) { xcTestDevice.stop(APP_ID) }
        verify(exactly = 1) { deviceController.stop(APP_ID) }
    }

    @Test
    fun `stop does not invoke the fallback when XCTest termination succeeds`() {
        val xcTestDevice = mockk<XCTestIOSDevice>()
        val deviceController = mockk<IOSDevice>()
        justRun { xcTestDevice.stop(APP_ID) }
        val device = LocalIOSDevice(DEVICE_ID, xcTestDevice, deviceController)

        device.stop(APP_ID)

        verify(exactly = 1) { xcTestDevice.stop(APP_ID) }
        verify(exactly = 0) { deviceController.stop(any()) }
    }

    @Test
    fun `stop reports fallback failure with the XCTest failure attached`() {
        val runnerFailure = IllegalStateException("runner unavailable")
        val fallbackFailure = IllegalStateException("go-ios unavailable")
        val xcTestDevice = mockk<XCTestIOSDevice>()
        val deviceController = mockk<IOSDevice>()
        every { xcTestDevice.stop(APP_ID) } throws runnerFailure
        every { deviceController.stop(APP_ID) } throws fallbackFailure
        val device = LocalIOSDevice(DEVICE_ID, xcTestDevice, deviceController)

        val thrown = assertThrows<IllegalStateException> { device.stop(APP_ID) }

        assertSame(fallbackFailure, thrown)
        assertSame(runnerFailure, thrown.suppressed.single())
    }

    private companion object {
        const val DEVICE_ID = "00008110-TEST-DEVICE"
        const val APP_ID = "com.example.app"
    }
}
