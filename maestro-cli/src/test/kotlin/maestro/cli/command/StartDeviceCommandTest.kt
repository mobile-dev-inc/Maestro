package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.cli.util.EnvUtils
import maestro.device.CPU_ARCHITECTURE
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
        // Stub resolver returns nothing → the spec falls back to its default image.
        val spec = parse("--platform", "android").buildDeviceSpec(Platform.ANDROID) { _, _ -> null }

        assertThat(spec).isInstanceOf(DeviceSpec.Android::class.java)
        assertThat((spec as DeviceSpec.Android).systemImage)
            .isEqualTo("system-images;android-33;google_apis;${EnvUtils.getMacOSArchitecture().value}")
    }

    @Test
    fun `a resolved image is baked into the spec, adopting its minor-versioned os`() {
        val abi = EnvUtils.getMacOSArchitecture().value
        val resolved = "system-images;android-37.1;google_apis_ps16k;$abi"
        val spec = parse("--platform", "android", "--device-os", "android-37")
            .buildDeviceSpec(Platform.ANDROID) { _, _ -> resolved } as DeviceSpec.Android

        assertThat(spec.os).isEqualTo("android-37.1")
        assertThat(spec.systemImage).isEqualTo(resolved)
        assertThat(spec.osVersion).isEqualTo(37)
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
    fun `help output documents the full-path device-os form and drops the dedicated flag`() {
        // picocli wraps long option descriptions across lines, so assert on a fragment that
        // survives wrapping rather than the whole "system-images;..." literal.
        val help = CommandLine(StartDeviceCommand()).usageMessage
        assertThat(help).doesNotContain("--android-system-image")
        assertThat(help).contains("Android system image")
        assertThat(help).contains("system-images;")
    }
}
