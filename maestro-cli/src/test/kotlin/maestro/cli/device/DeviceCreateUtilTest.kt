package maestro.cli.device

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import maestro.cli.CliError
import maestro.cli.command.StartDeviceCommand
import maestro.cli.util.EnvUtils
import maestro.device.Device
import maestro.device.DeviceService
import maestro.device.DeviceSpec
import maestro.device.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import picocli.CommandLine

class DeviceCreateUtilTest {

    private val existingGoogleApisAvd = "Maestro_ANDROID_pixel_6_android-33"
    private val abi = EnvUtils.getMacOSArchitecture().value

    @BeforeEach
    fun setUp() {
        mockkObject(DeviceService)
        every { DeviceService.isDeviceConnected(any(), any()) } returns null
        every { DeviceService.isAndroidSystemImageInstalled(any()) } returns true
        every { DeviceService.createAndroidDevice(any(), any(), any(), any()) } returns "avd-just-created"
        every { DeviceService.resolveSystemImage(any(), any()) } returns null
        every { DeviceService.isDeviceAvailableToLaunch(any(), Platform.ANDROID) } answers {
            val requestedName = firstArg<String>()
            if (existingGoogleApisAvd.equals(requestedName, ignoreCase = true)) {
                Device.AvailableForLaunch(
                    modelId = existingGoogleApisAvd,
                    description = existingGoogleApisAvd,
                    platform = Platform.ANDROID,
                    deviceType = Device.DeviceType.EMULATOR,
                    deviceSpec = DeviceSpec.Android(model = "pixel_6", os = "android-33"),
                )
            } else null
        }
    }

    @AfterEach
    fun tearDown() = unmockkObject(DeviceService)

    private fun specFromCli(vararg args: String): DeviceSpec {
        val command = StartDeviceCommand()
        CommandLine(command).parseArgs(*args)
        return command.buildDeviceSpec(Platform.ANDROID)
    }

    private fun createdImage(): String {
        val image = slot<String>()
        verify { DeviceService.createAndroidDevice(any(), any(), systemImage = capture(image), any()) }
        return image.captured
    }

    @Test
    fun `a playstore request does not reuse the google_apis emulator of the same model and os`() {
        val spec = specFromCli(
            "--platform", "android",
            "--device-os", "system-images;android-33;google_apis_playstore;$abi",
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
        verify(exactly = 0) { DeviceService.createAndroidDevice(any(), any(), any(), any()) }
    }

    // Image existence: the spec is respected; a missing image fails with the exact flag to use.

    @Test
    fun `an os whose derived image does not exist fails and names the image to pass as device-os`() {
        // android-37 has no platform of its own; the spec's os-aware default derives a ps16k image
        // that still is not installable, so the host resolves the real minor-versioned one to suggest.
        val derived = "system-images;android-37;google_apis_ps16k;$abi"
        val offered = "system-images;android-37.1;google_apis_ps16k;$abi"
        every { DeviceService.isAndroidSystemImageInstalled(derived) } returns false
        every { DeviceService.resolveSystemImage("android-37", EnvUtils.getMacOSArchitecture()) } returns offered
        val spec = specFromCli("--platform", "android", "--device-os", "android-37") as DeviceSpec.Android

        val error = assertThrows<CliError> { DeviceCreateUtil.getOrCreateAndroidDevice(spec, forceCreate = false) }

        assertThat(error.message).contains("--device-os $offered")
        assertThat(spec.os).isEqualTo("android-37")
        verify(exactly = 0) { DeviceService.createAndroidDevice(any(), any(), any(), any()) }
    }

    @Test
    fun `an installed derived image is used without asking the SDK`() {
        val spec = specFromCli("--platform", "android", "--device-os", "android-34") as DeviceSpec.Android

        DeviceCreateUtil.getOrCreateAndroidDevice(spec, forceCreate = false)

        assertThat(createdImage()).isEqualTo("system-images;android-34;google_apis;$abi")
        verify(exactly = 0) { DeviceService.resolveSystemImage(any(), any()) }
    }

    @Test
    fun `a missing image the SDK does offer falls through to the install prompt`() {
        val derived = "system-images;android-34;google_apis;$abi"
        every { DeviceService.isAndroidSystemImageInstalled(derived) } returns false
        every { DeviceService.resolveSystemImage("android-34", EnvUtils.getMacOSArchitecture()) } returns derived
        every { DeviceService.getAndroidSystemImageInstallCommand(derived) } returns "sdkmanager \"$derived\""
        val spec = specFromCli("--platform", "android", "--device-os", "android-34") as DeviceSpec.Android

        // No stdin in tests, so the prompt reads as "no" and the manual install command is shown.
        val error = assertThrows<CliError> { DeviceCreateUtil.getOrCreateAndroidDevice(spec, forceCreate = false) }

        assertThat(error.message).contains("sdkmanager \"$derived\"")
        assertThat(error.message).doesNotContain("--device-os")
    }

    @Test
    fun `a pinned full-path image is used as-is`() {
        val pinned = "system-images;android-37.1;google_apis_ps16k;$abi"
        val spec = specFromCli("--platform", "android", "--device-os", pinned) as DeviceSpec.Android

        DeviceCreateUtil.getOrCreateAndroidDevice(spec, forceCreate = false)

        assertThat(createdImage()).isEqualTo(pinned)
        verify(exactly = 0) { DeviceService.resolveSystemImage(any(), any()) }
    }

    @Test
    fun `reusing an existing emulator does not ask the SDK`() {
        val spec = specFromCli("--platform", "android", "--device-os", "android-33") as DeviceSpec.Android

        DeviceCreateUtil.getOrCreateAndroidDevice(spec, forceCreate = false)

        verify(exactly = 0) { DeviceService.resolveSystemImage(any(), any()) }
    }
}
