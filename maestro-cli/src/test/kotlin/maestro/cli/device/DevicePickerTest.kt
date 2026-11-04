package maestro.cli.device

import com.google.common.truth.Truth.assertThat
import maestro.cli.CliError
import maestro.cli.cloud.CloudDevicePicker
import maestro.cli.device.DevicePicker.Picked
import maestro.device.Platform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DevicePickerTest {

    // Shaped like production `/v2/device/list`: families mixed with variants and tablets,
    // and OS ids whose string order differs from version order.
    private val ios = mapOf(
        "iPad-Pro" to listOf("iOS-16-2", "iOS-16-4"),
        "iPhone-11" to listOf("iOS-16-2", "iOS-17-5", "iOS-18-2"),
        "iPhone-16e" to listOf("iOS-18-2", "iOS-26-2"),
        "iPhone-17" to listOf("iOS-26-2"),
        "iPhone-17-Pro" to listOf("iOS-26-2"),
        "iPhone-9" to listOf("iOS-16-2"),
    )
    private val android = mapOf(
        "pixel_6" to listOf("android-29", "android-33", "android-34", "android-37.1"),
        "pixel_9_pro_xl" to listOf("android-35", "android-37.1"),
        "pixel_10" to listOf("android-36", "android-37", "android-37.1"),
    )

    private fun pick(platform: Platform, model: String?, os: String?): Picked =
        DevicePicker.pick(platform, model, os, CloudDevicePicker.CLI) { if (platform == Platform.IOS) ios else android }

    @Test
    fun `nothing given picks the newest main-family phone and its newest OS`() {
        assertThat(pick(Platform.IOS, null, null)).isEqualTo(Picked("iPhone-17", "iOS-26-2"))
        // Numeric, not string, order: pixel_10 beats pixel_6 and android-37.1 beats android-37.
        assertThat(pick(Platform.ANDROID, null, null)).isEqualTo(Picked("pixel_10", "android-37.1"))

        val error = assertThrows<CliError> {
            DevicePicker.pick(Platform.IOS, null, null, CloudDevicePicker.CLI) { mapOf("iPad-Pro" to listOf("iOS-16-2")) }
        }
        assertThat(error.message).contains("No iPhone is available on Maestro Cloud")
    }

    @Test
    fun `only an OS picks the newest phone that offers it, expanding shorthands`() {
        // iPhone-17 has no iOS 18.2; the newest phone that has it.
        assertThat(pick(Platform.IOS, null, "18")).isEqualTo(Picked("iPhone-11", "iOS-18-2"))
        // An exact version beats a newer minor.
        assertThat(pick(Platform.ANDROID, null, "37")).isEqualTo(Picked("pixel_10", "android-37"))

        // Only an iPad offers it: no guessing, ask for the model.
        val error = assertThrows<CliError> { pick(Platform.IOS, null, "iOS-16-4") }
        assertThat(error.message).isEqualTo(
            "No iPhone on Maestro Cloud offers iOS-16-4. Pass --device-model too, or run `maestro list-cloud-devices` to see the available devices."
        )
    }

    @Test
    fun `only a model picks its newest OS, matching the model in any case`() {
        assertThat(pick(Platform.IOS, "iphone-11", null)).isEqualTo(Picked("iPhone-11", "iOS-18-2"))

        val error = assertThrows<CliError> { pick(Platform.IOS, "iPhone-99", null) }
        assertThat(error.message).contains("Pass --device-os too")
    }

    @Test
    fun `model and OS are canonicalized when listed and otherwise sent as given`() {
        assertThat(pick(Platform.IOS, "IPHONE-11", "ios-17-5")).isEqualTo(Picked("iPhone-11", "iOS-17-5"))
        // Hidden devices aren't listed; Cloud validates them.
        assertThat(pick(Platform.IOS, "iPhone-12-mini", "iOS-18-2")).isEqualTo(Picked("iPhone-12-mini", "iOS-18-2"))
        // A failed list fetch doesn't block a fully named device.
        assertThat(DevicePicker.pick(Platform.IOS, "iPhone-11", "iOS-17-5", CloudDevicePicker.CLI) { throw CliError("offline") })
            .isEqualTo(Picked("iPhone-11", "iOS-17-5"))
    }
}
