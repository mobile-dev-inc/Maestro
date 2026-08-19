package maestro.cli.session

import com.google.common.truth.Truth.assertThat
import maestro.device.Platform
import maestro.orchestra.devicecore.DeviceGateway
import maestro.orchestra.devicecore.RealDeviceGateway
import org.junit.jupiter.api.Test

/**
 * The device-core provisioning path yields a [RealDeviceGateway] and threads the resolved serial
 * through to the block.
 *
 * [MaestroSessionManager.provisionDeviceCore] is the core that
 * [MaestroSessionManager.newDeviceCoreSession] delegates to after resolving the device (the
 * `selectDevice` part needs a live adb/simctl target, so the CLI wiring is covered end-to-end in the
 * e2e; this unit test exercises the provisioning core directly with the real driver factory).
 *
 * The original "constructs NO legacy Maestro" assertion is now structural rather than tested: the
 * `maestro.Maestro` facade was deleted in the device-core converge, so this path cannot construct one.
 */
class DeviceCoreSessionTest {

    @Test
    fun `provisionDeviceCore yields a device-core driver and threads the serial through`() {
        var received: DeviceGateway? = null
        var receivedSerial: String? = "unset"
        MaestroSessionManager.provisionDeviceCore(
            platform = Platform.ANDROID,
            deviceId = "emulator-5554",
        ) { driver, platform, serial ->
            received = driver
            receivedSerial = serial
            assertThat(platform).isEqualTo(Platform.ANDROID)
        }

        assertThat(received).isInstanceOf(RealDeviceGateway::class.java)
        // The resolved serial is threaded through to the block so the caller can name the target with it.
        assertThat(receivedSerial).isEqualTo("emulator-5554")
    }
}
