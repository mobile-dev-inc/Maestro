package maestro.drivers

import device.IOSDevice
import io.mockk.mockk
import io.mockk.verifyOrder
import ios.LocalIOSDevice
import ios.xctest.XCTestIOSDevice
import maestro.device.DeviceOrientation
import org.junit.jupiter.api.Test

class IOSDriverSetOrientationTest {

    @Test
    fun `setOrientation turns the device controller first, then XCTest`() {
        // The controller is what turns a foldable simulator (SimctlIOSDevice posts Device Hub's rotate event);
        // XCTest's own setter comes second so that the screen has turned by the time the command returns.
        val xcTestDevice = mockk<XCTestIOSDevice>(relaxed = true)
        val deviceController = mockk<IOSDevice>(relaxed = true)
        val driver = IOSDriver(LocalIOSDevice("SIM-ID", xcTestDevice, deviceController))

        driver.setOrientation(DeviceOrientation.LANDSCAPE_LEFT)

        verifyOrder {
            deviceController.setOrientation("landscapeLeft")
            xcTestDevice.setOrientation("landscapeLeft")
        }
    }
}
