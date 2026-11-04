package maestro.cli.device

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.truth.Truth.assertThat
import maestro.cli.CliError
import maestro.cli.device.DevicePicker.Picked
import maestro.device.CPU_ARCHITECTURE
import maestro.device.Platform
import maestro.device.util.AndroidEnvUtils
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import util.SimctlList

class LocalDevicesTest {

    private fun pick(platform: Platform, devices: Map<String, List<String>>) =
        DevicePicker.pick(platform, model = null, os = null, LocalDevices.SOURCE) { devices }

    @Test
    fun `iOS offers each available iOS runtime's device types, and start-device takes the newest iPhone`() {
        // Trimmed `xcrun simctl list -j`: two iOS runtimes, one unavailable runtime, and a tvOS one.
        fun type(id: String) = """{"identifier": "com.apple.CoreSimulator.SimDeviceType.$id", "name": "$id", "bundlePath": "", "productFamily": "iPhone"}"""
        fun runtime(id: String, available: Boolean, vararg types: String) = """
            {"identifier": "com.apple.CoreSimulator.SimRuntime.$id", "isAvailable": $available, "version": "", "isInternal": false,
             "buildversion": "", "platform": "", "bundlePath": "", "runtimeRoot": "", "name": "$id",
             "supportedDeviceTypes": [${types.joinToString { type(it) }}]}
        """
        val simulators = jacksonObjectMapper().readValue<SimctlList>(
            """
            {"devicetypes": [], "devices": {}, "pairs": {}, "runtimes": [
              ${runtime("iOS-18-2", true, "iPhone-16", "iPhone-16e", "iPad-Pro-11-inch-M4")},
              ${runtime("iOS-26-2", true, "iPhone-17", "iPhone-17-Pro", "iPhone-16")},
              ${runtime("iOS-27-0", false, "iPhone-18")},
              ${runtime("tvOS-26-5", true, "Apple-TV-4K-3rd-generation-4K")}
            ]}
            """
        )

        val devices = LocalDevices.ios(simulators)

        assertThat(devices).isEqualTo(
            mapOf(
                "iPhone-16" to listOf("iOS-18-2", "iOS-26-2"),
                "iPhone-16e" to listOf("iOS-18-2"),
                "iPad-Pro-11-inch-M4" to listOf("iOS-18-2"),
                "iPhone-17" to listOf("iOS-26-2"),
                "iPhone-17-Pro" to listOf("iOS-26-2"),
            )
        )
        assertThat(pick(Platform.IOS, devices)).isEqualTo(Picked("iPhone-17", "iOS-26-2"))
    }

    @Test
    fun `Android offers Pixel profiles with the installed images start-device creates from, so nothing is downloaded`() {
        // From `sdkmanager --list_installed` on an arm64 Mac: playstore duplicates, a 37.0 image
        // without the 16 KB tag the CLI creates 37.x from, and an x86_64 image.
        val installed = listOf(
            "system-images;android-33;google_apis;arm64-v8a",
            "system-images;android-33;google_apis_playstore;arm64-v8a",
            "system-images;android-35;google_apis;arm64-v8a",
            "system-images;android-36;google_apis_x86;x86_64",
            "system-images;android-37.0;google_apis;arm64-v8a",
            "system-images;android-37.1;google_apis_playstore_ps16k;arm64-v8a",
            "system-images;android-37.1;google_apis_ps16k;arm64-v8a",
        )
        // From `avdmanager list device`.
        val profiles = AndroidEnvUtils.parsePixelDevices(
            """
            id: 0 or "automotive_1024p_landscape"
                Name: Automotive (1024p landscape)
            ---------
            id: 31 or "pixel_6"
                Name: Pixel 6
            ---------
            id: 46 or "pixel_9"
                Name: Pixel 9
            ---------
            id: 47 or "pixel_9_pro"
                Name: Pixel 9 Pro
            ---------
            """.trimIndent() + "\n"
        ).map { it.nameId }

        val devices = LocalDevices.android(installed, profiles, CPU_ARCHITECTURE.ARM64)

        val osIds = listOf("android-33", "android-35", "android-37.1")
        assertThat(devices).isEqualTo(mapOf("pixel_6" to osIds, "pixel_9" to osIds, "pixel_9_pro" to osIds))
        assertThat(pick(Platform.ANDROID, devices)).isEqualTo(Picked("pixel_9", "android-37.1"))

        // An explicit full image is offered whatever its tag.
        val explicit = LocalDevices.android(listOf("system-images;android-33;google_apis_playstore;arm64-v8a"), profiles, CPU_ARCHITECTURE.ARM64, anyTag = true)
        assertThat(explicit.getValue("pixel_9")).containsExactly("android-33")
    }

    @Test
    fun `nothing installed asks for the device and says what to install`() {
        assertThat(LocalDevices.android(emptyList(), listOf("pixel_9"), CPU_ARCHITECTURE.ARM64)).isEmpty()

        val error = assertThrows<CliError> { pick(Platform.ANDROID, emptyMap()) }
        assertThat(error.message).isEqualTo(
            "No Pixel phone is available on this machine. Pass --device-model and --device-os, or " +
                "install an iOS simulator runtime in Xcode, or an Android system image with sdkmanager."
        )
    }
}
