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

    // What this machine can create, as LocalDevices reports it; the image path is passed on.
    private val installed = mapOf("pixel_6" to listOf("android-33", "android-35"), "pixel_9" to listOf("android-35"))
    private fun local(devices: Map<String, List<String>>) = { _: Platform, _: String? -> devices }

    @Test
    fun `unset flags pick the newest device this machine can create, and say so`() {
        val out = java.io.ByteArrayOutputStream()
        val original = System.out
        System.setOut(java.io.PrintStream(out))
        val spec = try {
            parse("--platform", "android", "--device-locale", "de_DE").buildDeviceSpec(Platform.ANDROID, localDevices = local(installed))
        } finally {
            System.setOut(original)
        }

        assertThat(spec).isEqualTo(
            DeviceSpec.Android(
                model = "pixel_9",
                os = "android-35",
                locale = maestro.device.locale.AndroidLocale.fromString("de_DE"),
                cpuArchitecture = EnvUtils.getMacOSArchitecture(),
            )
        )
        assertThat(out.toString()).contains("Using pixel_9 / android-35.")

        // The deprecated --os-version maps per platform: here to android-33, which only pixel_6 has.
        assertThat(parse("--platform", "android", "--os-version", "33").buildDeviceSpec(Platform.ANDROID, localDevices = local(installed)).model)
            .isEqualTo("pixel_6")
    }

    @Test
    fun `a model and OS are used as given, without reading what's installed`() {
        val spec = parse("--platform", "ios", "--device-model", "iPhone-15", "--device-os", "iOS-17-5")
            .buildDeviceSpec(Platform.IOS) { _, _ -> throw AssertionError("must not read the local devices") }

        assertThat(spec).isEqualTo(DeviceSpec.Ios(model = "iPhone-15", os = "iOS-17-5"))
    }

    @Test
    fun `a full-path device-os without a model picks the newest Pixel for that image`() {
        val abi = EnvUtils.getMacOSArchitecture().value
        var imageSeen: String? = null
        val spec = parse(
            "--platform", "android",
            "--device-os", "system-images;android-34;google_apis_playstore;$abi",
        ).buildDeviceSpec(Platform.ANDROID) { _, image -> imageSeen = image; mapOf("pixel_9" to listOf("android-34")) } as DeviceSpec.Android

        assertThat(imageSeen).isEqualTo("system-images;android-34;google_apis_playstore;$abi")
        assertThat(spec.model).isEqualTo("pixel_9")
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
            ).buildDeviceSpec(Platform.ANDROID, localDevices = local(mapOf("pixel_9" to listOf("android-34"))))
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
