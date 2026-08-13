package maestro.cli.session

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import maestro.Maestro
import maestro.device.Platform
import maestro.orchestra.devicecore.DeviceCoreDriver
import maestro.orchestra.devicecore.RealDeviceCoreDriver
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * DoD #3: the device-core provisioning path must construct NO legacy [Maestro].
 *
 * [MaestroSessionManager.provisionDeviceCore] is the Maestro-less core that
 * [MaestroSessionManager.newDeviceCoreSession] delegates to after resolving the device (the
 * `selectDevice` part needs a live adb/simctl target, so the CLI wiring is covered end-to-end in the
 * Task 9 e2e; this unit test exercises the provisioning core directly with the real driver factory).
 *
 * Every legacy `Maestro` the CLI builds goes through one of the three companion factories
 * `Maestro.android` / `Maestro.ios` / `Maestro.web` (the `Maestro(...)` constructor is private to
 * those factories). We [mockkObject] the companion and stub the factories, so a call is both
 * harmless AND recorded — then assert (`exactly = 0`) that none fired. This genuinely fails if
 * provisioning ever constructs a `Maestro`, regardless of `openDriver`, because it catches the
 * construction at its only entry point rather than relying on a method being called on the instance.
 */
class DeviceCoreSessionTest {

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `provisionDeviceCore constructs no legacy Maestro and yields a device-core driver`() {
        mockkObject(Maestro.Companion)
        every { Maestro.android(any(), any()) } returns mockk(relaxed = true)
        every { Maestro.ios(any(), any()) } returns mockk(relaxed = true)
        every { Maestro.web(any(), any(), any()) } returns mockk(relaxed = true)

        var received: DeviceCoreDriver? = null
        MaestroSessionManager.provisionDeviceCore(
            platform = Platform.ANDROID,
            deviceId = null,
        ) { driver, platform ->
            received = driver
            assertThat(platform).isEqualTo(Platform.ANDROID)
        }

        assertThat(received).isInstanceOf(RealDeviceCoreDriver::class.java)
        verify(exactly = 0) { Maestro.android(any(), any()) }
        verify(exactly = 0) { Maestro.ios(any(), any()) }
        verify(exactly = 0) { Maestro.web(any(), any(), any()) }
    }
}
