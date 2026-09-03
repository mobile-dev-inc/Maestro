package maestro.cli.device

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import maestro.cli.command.StartDeviceCommand
import maestro.cli.util.EnvUtils
import maestro.device.CPU_ARCHITECTURE
import maestro.device.Device
import maestro.device.DeviceService
import maestro.device.DeviceSpec
import maestro.device.Platform
import maestro.device.SystemImageTag
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import picocli.CommandLine

class DeviceCreateUtilTest {

    private val existingGoogleApisAvd = "Maestro_ANDROID_pixel_6_android-33"

    @BeforeEach
    fun setUp() {
        mockkObject(DeviceService)
        every { DeviceService.isDeviceConnected(any(), any()) } returns null
        every { DeviceService.isAndroidSystemImageInstalled(any()) } returns true
        every { DeviceService.createAndroidDevice(any(), any(), any(), any()) } returns "avd-just-created"
        // Default: the SDK offers nothing extra, so resolution falls back to the naive package.
        every { DeviceService.resolveSystemImage(any(), any(), any()) } returns null
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

    @Test
    fun `a playstore request does not reuse the google_apis emulator of the same model and os`() {
        val abi = EnvUtils.getMacOSArchitecture().value
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

    @Test
    fun `reusing an existing emulator does not ask the SDK to resolve an image`() {
        val spec = specFromCli("--platform", "android", "--device-os", "android-33") as DeviceSpec.Android

        DeviceCreateUtil.getOrCreateAndroidDevice(spec, forceCreate = false)

        verify(exactly = 0) { DeviceService.resolveSystemImage(any(), any(), any()) }
    }

    @Test
    fun `android-37 is created with the image the SDK resolves for it`() {
        val abi = EnvUtils.getMacOSArchitecture()
        val offered = "system-images;android-37.1;google_apis_ps16k;${abi.value}"
        every { DeviceService.resolveSystemImage("android-37", SystemImageTag.GOOGLE_APIS, abi) } returns offered
        every { DeviceService.isAndroidSystemImageInstalled(offered) } returns true
        val spec = specFromCli("--platform", "android", "--device-os", "android-37") as DeviceSpec.Android

        DeviceCreateUtil.getOrCreateAndroidDevice(spec, forceCreate = false)

        val createdImage = slot<String>()
        verify { DeviceService.createAndroidDevice(any(), any(), systemImage = capture(createdImage), any()) }
        assertThat(createdImage.captured).isEqualTo(offered)
    }

    @Test
    fun `a full-path device-os is decomposed into os and tag intent`() {
        val abi = EnvUtils.getMacOSArchitecture().value
        val spec = specFromCli(
            "--platform", "android",
            "--device-os", "system-images;android-37.1;google_apis_ps16k;$abi",
        ) as DeviceSpec.Android

        assertThat(spec.os).isEqualTo("android-37.1")
        assertThat(spec.tag).isEqualTo(SystemImageTag.GOOGLE_APIS)
    }
}
