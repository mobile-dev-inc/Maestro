package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.cli.util.EnvUtils
import maestro.device.CPU_ARCHITECTURE
import maestro.device.DeviceSpec
import maestro.device.Platform
import maestro.device.locale.AndroidLocale
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
    fun `a full-path device-os reaches the constructed Android spec's systemImage`() {
        val abi = EnvUtils.getMacOSArchitecture().value
        val spec = parse(
            "--platform", "android",
            "--device-os", "system-images;android-34;google_apis_playstore;$abi",
        ).buildDeviceSpec(Platform.ANDROID) as DeviceSpec.Android

        assertThat(spec.os).isEqualTo("android-34")
        assertThat(spec.systemImage)
            .isEqualTo("system-images;android-34;google_apis_playstore;$abi")
    }

    @Test
    fun `a full-path device-os with an abi that doesn't match this host throws`() {
        // cpuArchitecture always reflects the actual host (EnvUtils.getMacOSArchitecture()), so a
        // full-path --device-os naming a different abi fails DeviceSpec.Android's own consistency
        // check rather than silently launching the wrong image.
        val mismatchedAbi = if (EnvUtils.getMacOSArchitecture() == CPU_ARCHITECTURE.ARM64) "x86_64" else "arm64-v8a"

        assertThrows<IllegalArgumentException> {
            parse(
                "--platform", "android",
                "--device-os", "system-images;android-34;google_apis;$mismatchedAbi",
            ).buildDeviceSpec(Platform.ANDROID)
        }
    }

    @Test
    fun `a version-shaped device-os sets os without a systemImage override`() {
        val spec = parse(
            "--platform", "android",
            "--device-os", "android-34",
        ).buildDeviceSpec(Platform.ANDROID) as DeviceSpec.Android

        assertThat(spec.os).isEqualTo("android-34")
        assertThat(spec.systemImageOverride).isNull()
    }

    @Test
    fun `os-version alone derives the android- prefixed os when device-os is unset`() {
        val spec = parse(
            "--platform", "android",
            "--os-version", "34",
        ).buildDeviceSpec(Platform.ANDROID) as DeviceSpec.Android

        assertThat(spec.os).isEqualTo("android-34")
        assertThat(spec.systemImageOverride).isNull()
    }

    @Test
    fun `a version-shaped device-os takes precedence over os-version`() {
        val spec = parse(
            "--platform", "android",
            "--device-os", "android-31",
            "--os-version", "34",
        ).buildDeviceSpec(Platform.ANDROID) as DeviceSpec.Android

        assertThat(spec.os).isEqualTo("android-31")
    }

    @Test
    fun `a full-path device-os derives os from its own segment, ignoring an unrelated os-version`() {
        val abi = EnvUtils.getMacOSArchitecture().value
        val spec = parse(
            "--platform", "android",
            "--device-os", "system-images;android-30;google_apis_playstore;$abi",
            "--os-version", "34",
        ).buildDeviceSpec(Platform.ANDROID) as DeviceSpec.Android

        assertThat(spec.os).isEqualTo("android-30")
        assertThat(spec.systemImageOverride).isEqualTo("system-images;android-30;google_apis_playstore;$abi")
    }

    @Test
    fun `device-model and device-locale flow through unchanged for a full-path device-os`() {
        val abi = EnvUtils.getMacOSArchitecture().value
        val spec = parse(
            "--platform", "android",
            "--device-os", "system-images;android-34;google_apis_playstore;$abi",
            "--device-model", "pixel_7",
            "--device-locale", "de_DE",
        ).buildDeviceSpec(Platform.ANDROID) as DeviceSpec.Android

        assertThat(spec.model).isEqualTo("pixel_7")
        assertThat(spec.locale).isEqualTo(AndroidLocale.fromString("de_DE"))
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
