package maestro.cli.device

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import maestro.cli.command.StartDeviceCommand
import maestro.device.Device
import maestro.device.DeviceService
import maestro.device.DeviceSpec
import maestro.device.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import picocli.CommandLine

/**
 * An AVD is looked up by [DeviceSpec.Android.deviceName]. If that name did not carry the
 * system-image tag, a `--android-system-image-tag google_apis_playstore` request would find the
 * `google_apis` emulator of the same model and os and silently launch it — the customer would
 * get the image they explicitly asked not to have.
 */
class DeviceCreateUtilTest {

    private val existingGoogleApisAvd = "Maestro_ANDROID_pixel_6_android-33"

    @BeforeEach
    fun setUp() {
        mockkObject(DeviceService)
        every { DeviceService.isDeviceConnected(any(), any()) } returns null
        every { DeviceService.isAndroidSystemImageInstalled(any()) } returns true
        every { DeviceService.createAndroidDevice(any(), any(), any(), any(), any(), any()) } returns
            "avd-just-created"
        // The only emulator on this machine is a google_apis one for pixel_6 / android-33.
        every { DeviceService.isDeviceAvailableToLaunch(any(), Platform.ANDROID) } answers {
            val requestedName = firstArg<String>()
            // DeviceService matches an Android AVD by its exact name (DeviceServiceTest covers
            // that); this fake models the same rule.
            if (existingGoogleApisAvd.equals(requestedName, ignoreCase = true)) {
                Device.AvailableForLaunch(
                    modelId = existingGoogleApisAvd,
                    description = existingGoogleApisAvd,
                    platform = Platform.ANDROID,
                    deviceType = Device.DeviceType.EMULATOR,
                    deviceSpec = DeviceSpec.Android(model = "pixel_6", os = "android-33"),
                )
            } else {
                null
            }
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(DeviceService)
    }

    private fun specFromCli(vararg args: String): DeviceSpec {
        val command = StartDeviceCommand()
        CommandLine(command).parseArgs(*args)
        return command.buildDeviceSpec(Platform.ANDROID)
    }

    @Test
    fun `a playstore request does not reuse the google_apis emulator of the same model and os`() {
        val spec = specFromCli(
            "--platform", "android",
            "--device-os", "android-33",
            "--android-system-image-tag", "google_apis_playstore",
        ) as DeviceSpec.Android

        val device = DeviceCreateUtil.getOrCreateAndroidDevice(spec, forceCreate = false)

        assertThat(device.modelId).isEqualTo("avd-just-created")
        val createdName = slot<String>()
        val createdImage = slot<String>()
        verify {
            DeviceService.createAndroidDevice(
                deviceName = capture(createdName),
                device = any(),
                systemImage = capture(createdImage),
                tag = "google_apis_playstore",
                abi = any(),
                force = any(),
            )
        }
        assertThat(createdName.captured).isNotEqualTo(existingGoogleApisAvd)
        assertThat(createdImage.captured).contains("google_apis_playstore")
    }

    @Test
    fun `an unflagged request still reuses the existing google_apis emulator`() {
        val spec = specFromCli("--platform", "android", "--device-os", "android-33") as DeviceSpec.Android

        val device = DeviceCreateUtil.getOrCreateAndroidDevice(spec, forceCreate = false)

        assertThat(device.modelId).isEqualTo(existingGoogleApisAvd)
        verify(exactly = 0) {
            DeviceService.createAndroidDevice(any(), any(), any(), any(), any(), any())
        }
    }
}
