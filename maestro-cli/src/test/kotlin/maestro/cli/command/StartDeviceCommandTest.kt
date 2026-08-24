package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.cli.util.EnvUtils
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
    fun `an unset flag leaves the Android spec on the default google_apis tag`() {
        val spec = parse("--platform", "android").buildDeviceSpec(Platform.ANDROID)

        assertThat(spec).isInstanceOf(DeviceSpec.Android::class.java)
        assertThat((spec as DeviceSpec.Android).tag).isEqualTo(SystemImageTag.GOOGLE_APIS)
        // start-device always builds for the host's architecture, so name it rather than
        // reading it back off the value under test.
        assertThat(spec).isEqualTo(
            DeviceSpec.Android.DEFAULT.copy(cpuArchitecture = EnvUtils.getMacOSArchitecture())
        )
    }

    @Test
    fun `the playstore tag reaches the constructed Android spec`() {
        val spec = parse(
            "--platform", "android",
            "--android-system-image", "google_apis_playstore",
        ).buildDeviceSpec(Platform.ANDROID)

        assertThat((spec as DeviceSpec.Android).tag)
            .isEqualTo(SystemImageTag.GOOGLE_APIS_PLAYSTORE)
    }

    @Test
    fun `the tag flows into the derived emulator image string`() {
        val spec = parse(
            "--platform", "android",
            "--device-os", "android-34",
            "--android-system-image", "google_apis_playstore",
        ).buildDeviceSpec(Platform.ANDROID) as DeviceSpec.Android

        assertThat(spec.emulatorImage).contains("google_apis_playstore")
    }

    @Test
    fun `picocli rejects a value outside the closed set`() {
        val error = assertThrows<CommandLine.ParameterException> {
            parse("--platform", "android", "--android-system-image", "aosp_atd")
        }

        assertThat(error).hasMessageThat().contains("aosp_atd")
    }

    @Test
    fun `help output lists the candidate tags`() {
        val help = CommandLine(StartDeviceCommand()).usageMessage

        assertThat(help).contains("--android-system-image")
        assertThat(help).contains("google_apis_playstore")
    }
}
