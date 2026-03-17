package maestro.device

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

internal class DeviceServiceTest {

    @TempDir
    lateinit var avdHome: File

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Creates <avdHome>/<avdName>.avd/config.ini with the given content. */
    private fun writeConfigIni(avdName: String, content: String) {
        val dir = File(avdHome, "$avdName.avd").also { it.mkdirs() }
        File(dir, "config.ini").writeText(content)
    }

    private fun List<AvdInfo>.named(name: String) = find { it.name == name }

    // -------------------------------------------------------------------------
    // Happy-path
    // -------------------------------------------------------------------------

    @Test
    fun `single AVD with all fields and config ini present`() {
        writeConfigIni(
            "Pixel_6_API_34",
            "image.sysdir.1=system-images/android-34/google_apis/arm64-v8a/\n"
        )

        val output = """
            Available Android Virtual Devices:
                Name: Pixel_6_API_34
              Device: pixel_6 (Google Pixel 6)
                Path: /home/user/.android/avd/Pixel_6_API_34.avd
              Target: Google APIs (Google Inc.)
                      Based on: Android 14.0 ("UpsideDownCake") Tag/ABI: google_apis/arm64-v8a
                Skin: pixel_6
              Sdcard: 512M
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result).hasSize(1)
        assertThat(result.named("Pixel_6_API_34")).isEqualTo(AvdInfo(name = "Pixel_6_API_34", model = "pixel_6", os = "android-34"))
    }

    @Test
    fun `multiple AVDs are all parsed`() {
        writeConfigIni("Pixel_6_API_34", "image.sysdir.1=system-images/android-34/google_apis/arm64-v8a/\n")
        writeConfigIni("Pixel_7_API_33", "image.sysdir.1=system-images/android-33/google_apis/x86_64/\n")

        val output = """
            Available Android Virtual Devices:
                Name: Pixel_6_API_34
              Device: pixel_6 (Google Pixel 6)
                Path: /home/user/.android/avd/Pixel_6_API_34.avd
              Target: Google APIs (Google Inc.)
                      Based on: Android 14.0 Tag/ABI: google_apis/arm64-v8a
            ---------
                Name: Pixel_7_API_33
              Device: pixel_7 (Google Pixel 7)
                Path: /home/user/.android/avd/Pixel_7_API_33.avd
              Target: Google APIs (Google Inc.)
                      Based on: Android 13.0 Tag/ABI: google_apis/x86_64
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result).hasSize(2)
        assertThat(result.named("Pixel_6_API_34")).isEqualTo(AvdInfo(name = "Pixel_6_API_34", model = "pixel_6", os = "android-34"))
        assertThat(result.named("Pixel_7_API_33")).isEqualTo(AvdInfo(name = "Pixel_7_API_33", model = "pixel_7", os = "android-33"))
    }

    // -------------------------------------------------------------------------
    // Model (Device:) field edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `Device field with no parenthetical description uses full token as model`() {
        writeConfigIni("Pixel_6_API_34", "image.sysdir.1=system-images/android-34/google_apis/arm64-v8a/\n")

        val output = """
            Available Android Virtual Devices:
                Name: Pixel_6_API_34
              Device: pixel_6
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result.named("Pixel_6_API_34")).isEqualTo(AvdInfo(name = "Pixel_6_API_34", model = "pixel_6", os = ""))
    }

    @Test
    fun `missing Device field results in empty model string`() {
        writeConfigIni("Pixel_6_API_34", "image.sysdir.1=system-images/android-34/google_apis/arm64-v8a/\n")

        val output = """
            Available Android Virtual Devices:
                Name: Pixel_6_API_34
                Path: /home/user/.android/avd/Pixel_6_API_34.avd
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result.named("Pixel_6_API_34")).isEqualTo(AvdInfo(name = "Pixel_6_API_34", model = "", os = "android-34"))
    }

    // -------------------------------------------------------------------------
    // OS / config.ini edge cases
    // -------------------------------------------------------------------------

    @Test
    fun `config ini missing results in empty OS string`() {
        // No config.ini written for this AVD

        val output = """
            Available Android Virtual Devices:
                Name: No_Config_AVD
              Device: pixel_6 (Google Pixel 6)
                Path: /home/user/.android/avd/No_Config_AVD.avd
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result.named("No_Config_AVD")).isEqualTo(AvdInfo(name = "No_Config_AVD", model = "pixel_6", os = ""))
    }

    @Test
    fun `config ini present but lacks image sysdir line results in empty OS string`() {
        writeConfigIni("AVD_No_Sysdir", "hw.ramSize=2048\ntarget=android-34\n")

        // Path: line triggers the config.ini fallback branch
        val output = """
            Available Android Virtual Devices:
                Name: AVD_No_Sysdir
              Device: pixel_6 (Google Pixel 6)
                Path: /home/user/.android/avd/AVD_No_Sysdir.avd
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result.named("AVD_No_Sysdir")).isEqualTo(AvdInfo(name = "AVD_No_Sysdir", model = "pixel_6", os = ""))
    }

    @Test
    fun `config ini sysdir with no android-XX segment results in empty OS string`() {
        writeConfigIni("AVD_Weird_Sysdir", "image.sysdir.1=custom-images/vendor-image/arm64-v8a/\n")

        // Path: line triggers the config.ini fallback branch
        val output = """
            Available Android Virtual Devices:
                Name: AVD_Weird_Sysdir
              Device: pixel_6 (Google Pixel 6)
                Path: /home/user/.android/avd/AVD_Weird_Sysdir.avd
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result.named("AVD_Weird_Sysdir")).isEqualTo(AvdInfo(name = "AVD_Weird_Sysdir", model = "pixel_6", os = ""))
    }

    @Test
    fun `config ini sysdir with android-XX at a non-first segment is still found`() {
        writeConfigIni(
            "Nested_AVD",
            "image.sysdir.1=sdk/system-images/android-30/google_apis_playstore/x86/\n"
        )

        // A non-Name/non-Device line (e.g. Path:) is required to trigger the
        // config.ini fallback branch — it only fires on "other" lines.
        val output = """
            Available Android Virtual Devices:
                Name: Nested_AVD
              Device: pixel_4 (Google Pixel 4)
                Path: /home/user/.android/avd/Nested_AVD.avd
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result.named("Nested_AVD")).isEqualTo(AvdInfo(name = "Nested_AVD", model = "pixel_4", os = "android-30"))
    }

    // -------------------------------------------------------------------------
    // Empty / degenerate input
    // -------------------------------------------------------------------------

    @Test
    fun `empty output returns empty list`() {
        val result = DeviceService.parseAvdInfo("", avdHome)
        assertThat(result).isEmpty()
    }

    @Test
    fun `output with no AVDs returns empty list`() {
        val output = "Available Android Virtual Devices:\n"
        val result = DeviceService.parseAvdInfo(output, avdHome)
        assertThat(result).isEmpty()
    }

    @Test
    fun `Name line with no subsequent Device or config ini still saved with empty values`() {
        // AVD block containing only a Name: line — no Device:, no config.ini
        val output = """
            Available Android Virtual Devices:
                Name: Bare_AVD
        """.trimIndent()

        val result = DeviceService.parseAvdInfo(output, avdHome)

        assertThat(result).hasSize(1)
        assertThat(result.named("Bare_AVD")).isEqualTo(AvdInfo(name = "Bare_AVD", model = "", os = ""))
    }

}
