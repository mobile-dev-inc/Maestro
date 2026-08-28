package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.cli.util.EnvUtils
import maestro.device.DeviceSpec
import maestro.device.Platform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import picocli.CommandLine

class StartDeviceCommandTest {

    private fun parse(vararg args: String): StartDeviceCommand {
        val command = StartDeviceCommand()
        CommandLine(command).parseArgs(*args)
        return command
    }

    @Test
    fun `an unset flag leaves the Android spec on the default system image`() {
        val spec = parse("--platform", "android").buildDeviceSpec(Platform.ANDROID)

        assertThat(spec).isInstanceOf(DeviceSpec.Android::class.java)
        assertThat((spec as DeviceSpec.Android).systemImage)
            .isEqualTo("system-images;android-33;google_apis;${EnvUtils.getMacOSArchitecture().value}")
    }

    @Test
    fun `the flag reaches the constructed Android spec's systemImage`() {
        val spec = parse(
            "--platform", "android",
            "--device-os", "android-34",
            "--android-system-image", "system-images;android-34;google_apis_playstore;arm64-v8a",
        ).buildDeviceSpec(Platform.ANDROID) as DeviceSpec.Android

        assertThat(spec.systemImage)
            .isEqualTo("system-images;android-34;google_apis_playstore;arm64-v8a")
    }

    @Test
    fun `an os-mismatched image throws`() {
        assertThrows<IllegalArgumentException> {
            parse(
                "--platform", "android",
                "--device-os", "android-33",
                "--android-system-image", "system-images;android-34;google_apis;arm64-v8a",
            ).buildDeviceSpec(Platform.ANDROID)
        }
    }

    @Test
    fun `help output lists the new flag`() {
        val help = CommandLine(StartDeviceCommand()).usageMessage
        assertThat(help).contains("--android-system-image")
    }
}
