package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.cli.CliError
import maestro.cli.util.EnvUtils
import maestro.device.CPU_ARCHITECTURE
import maestro.device.DeviceSpec
import maestro.device.Platform
import maestro.device.SystemImageTag
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
    fun `an unset flag leaves the Android spec on the default os and tag`() {
        val spec = parse("--platform", "android").buildDeviceSpec(Platform.ANDROID)

        assertThat(spec).isInstanceOf(DeviceSpec.Android::class.java)
        spec as DeviceSpec.Android
        assertThat(spec.os).isEqualTo("android-33")
        assertThat(spec.tag).isEqualTo(SystemImageTag.GOOGLE_APIS)
    }

    @Test
    fun `a full-path device-os is decomposed into os and tag intent`() {
        val abi = EnvUtils.getMacOSArchitecture().value
        val spec = parse(
            "--platform", "android",
            "--device-os", "system-images;android-34;google_apis_playstore;$abi",
        ).buildDeviceSpec(Platform.ANDROID) as DeviceSpec.Android

        assertThat(spec.os).isEqualTo("android-34")
        assertThat(spec.tag).isEqualTo(SystemImageTag.GOOGLE_APIS_PLAYSTORE)
    }

    @Test
    fun `a full-path device-os with fewer than 4 segments throws a CliError`() {
        assertThrows<CliError> {
            parse("--platform", "android", "--device-os", "system-images;android-34;google_apis")
                .buildDeviceSpec(Platform.ANDROID)
        }
    }

    @Test
    fun `a full-path device-os with an unsupported tag throws a CliError`() {
        val abi = EnvUtils.getMacOSArchitecture().value
        assertThrows<CliError> {
            parse("--platform", "android", "--device-os", "system-images;android-34;aosp_atd;$abi")
                .buildDeviceSpec(Platform.ANDROID)
        }
    }

    @Test
    fun `a full-path device-os with an abi that doesn't match this host throws`() {
        // A local emulator must match the host abi, so a full-path --device-os naming a
        // different abi is rejected rather than silently launching the wrong image.
        val mismatchedAbi = if (EnvUtils.getMacOSArchitecture() == CPU_ARCHITECTURE.ARM64) "x86_64" else "arm64-v8a"

        assertThrows<CliError> {
            parse(
                "--platform", "android",
                "--device-os", "system-images;android-34;google_apis;$mismatchedAbi",
            ).buildDeviceSpec(Platform.ANDROID)
        }
    }

    @Test
    fun `help output documents the full-path device-os form and drops the dedicated flag`() {
        // picocli wraps long option descriptions across lines, so assert on a fragment that
        // survives wrapping rather than the whole "system-images;..." literal.
        val help = CommandLine(StartDeviceCommand()).usageMessage
        assertThat(help).doesNotContain("--android-system-image")
        assertThat(help).contains("Android system image")
        assertThat(help).contains("system-images;")
    }
}
