package maestro.cli.cloud

import com.google.common.truth.Truth.assertThat
import maestro.cli.CliError
import maestro.cli.cloud.CloudDevicePicker.CloudDevice
import maestro.device.Platform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CloudDevicePickerTest {

    // Shaped like production `/v2/device/list`: families mixed with variants and tablets,
    // and OS ids whose string order differs from version order.
    private val ios = mapOf(
        "iPad-Pro" to listOf("iOS-16-2", "iOS-16-4"),
        "iPhone-11" to listOf("iOS-16-2", "iOS-17-5", "iOS-18-2"),
        "iPhone-16e" to listOf("iOS-18-2", "iOS-26-2"),
        "iPhone-17" to listOf("iOS-26-2"),
        "iPhone-17-Pro" to listOf("iOS-26-2"),
        "iPhone-9" to listOf("iOS-16-2"),
        "iPhone-SE--3rd-generation-" to listOf("iOS-17-5", "iOS-18-2", "iOS-26-2"),
    )
    private val android = mapOf(
        "pixel_6" to listOf("android-29", "android-33", "android-34", "android-37.1"),
        "pixel_9_pro_xl" to listOf("android-35", "android-37.1"),
        "pixel_10" to listOf("android-36", "android-37", "android-37.1"),
        "pixel_tablet" to listOf("android-35", "android-37.1"),
    )

    private fun pick(platform: Platform, model: String?, os: String?): CloudDevice {
        val devices = when (platform) {
            Platform.IOS -> ios
            Platform.ANDROID -> android
            Platform.WEB -> mapOf("chromium" to listOf("default"))
        }
        return CloudDevicePicker.pick(platform, model, os) { devices }
    }

    @Test
    fun `nothing given picks the newest main-family phone and its newest OS`() {
        assertThat(pick(Platform.IOS, null, null)).isEqualTo(CloudDevice("iPhone-17", "iOS-26-2"))
        // Numeric, not string, order: pixel_10 beats pixel_6 and android-37.1 beats android-37.
        assertThat(pick(Platform.ANDROID, null, null)).isEqualTo(CloudDevice("pixel_10", "android-37.1"))
        assertThat(pick(Platform.WEB, null, null)).isEqualTo(CloudDevice("chromium", "default"))
    }

    @Test
    fun `only an OS picks the newest phone that offers it`() {
        // iPhone-17 has no iOS 18.2, so the next newest phone that has it.
        assertThat(pick(Platform.IOS, null, "iOS-18-2")).isEqualTo(CloudDevice("iPhone-11", "iOS-18-2"))
        // Shorthands expand to the newest matching OS.
        assertThat(pick(Platform.IOS, null, "18")).isEqualTo(CloudDevice("iPhone-11", "iOS-18-2"))
        assertThat(pick(Platform.ANDROID, null, "34")).isEqualTo(CloudDevice("pixel_6", "android-34"))
        assertThat(pick(Platform.ANDROID, null, "37")).isEqualTo(CloudDevice("pixel_10", "android-37"))
        assertThat(pick(Platform.ANDROID, null, "37.1")).isEqualTo(CloudDevice("pixel_10", "android-37.1"))
        // Only a non-family model offers it.
        assertThat(pick(Platform.IOS, null, "iOS-16-4")).isEqualTo(CloudDevice("iPad-Pro", "iOS-16-4"))

        val error = assertThrows<CliError> { pick(Platform.IOS, null, "iOS-15-0") }
        assertThat(error.message).contains("iOS-15-0 is not available on Maestro Cloud")
    }

    @Test
    fun `only a model picks its newest OS, matching the model in any case`() {
        assertThat(pick(Platform.IOS, "iphone-11", null)).isEqualTo(CloudDevice("iPhone-11", "iOS-18-2"))
        assertThat(pick(Platform.ANDROID, "pixel_tablet", null)).isEqualTo(CloudDevice("pixel_tablet", "android-37.1"))

        val error = assertThrows<CliError> { pick(Platform.IOS, "iPhone-99", null) }
        assertThat(error.message).contains("--device-os")
    }

    @Test
    fun `model and OS are canonicalized when listed and otherwise sent as given`() {
        assertThat(pick(Platform.IOS, "IPHONE-11", "ios-17-5")).isEqualTo(CloudDevice("iPhone-11", "iOS-17-5"))
        assertThat(pick(Platform.ANDROID, "pixel_6", "34")).isEqualTo(CloudDevice("pixel_6", "android-34"))
        // Hidden devices aren't listed; Cloud validates them.
        assertThat(pick(Platform.IOS, "iPhone-12-mini", "iOS-18-2")).isEqualTo(CloudDevice("iPhone-12-mini", "iOS-18-2"))
        // A failed list fetch doesn't block a fully named device.
        assertThat(CloudDevicePicker.pick(Platform.IOS, "iPhone-11", "iOS-17-5") { throw CliError("offline") })
            .isEqualTo(CloudDevice("iPhone-11", "iOS-17-5"))
    }

    @Test
    fun `a system image without a model takes the newest phone offering its OS, else the newest phone`() {
        assertThat(CloudDevicePicker.modelForSystemImage("android-34") { android }).isEqualTo("pixel_6")
        assertThat(CloudDevicePicker.modelForSystemImage("android-36") { android }).isEqualTo("pixel_10")
        assertThat(CloudDevicePicker.modelForSystemImage("android-99") { android }).isEqualTo("pixel_10")
    }
}
