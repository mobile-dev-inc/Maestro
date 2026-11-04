package maestro.cli.cloud

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import maestro.cli.CliError
import maestro.cli.api.ApiClient
import maestro.cli.cloud.CloudDevicePicker.CloudDevice
import maestro.cli.cloud.CloudDevicePicker.Flags
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
    )
    private val android = mapOf(
        "pixel_6" to listOf("android-29", "android-33", "android-34", "android-37.1"),
        "pixel_9_pro_xl" to listOf("android-35", "android-37.1"),
        "pixel_10" to listOf("android-36", "android-37", "android-37.1"),
    )

    private fun pick(platform: Platform, model: String?, os: String?): CloudDevice =
        CloudDevicePicker.pick(platform, model, os, Flags.CLI) { if (platform == Platform.IOS) ios else android }

    @Test
    fun `nothing given picks the newest main-family phone and its newest OS`() {
        assertThat(pick(Platform.IOS, null, null)).isEqualTo(CloudDevice("iPhone-17", "iOS-26-2"))
        // Numeric, not string, order: pixel_10 beats pixel_6 and android-37.1 beats android-37.
        assertThat(pick(Platform.ANDROID, null, null)).isEqualTo(CloudDevice("pixel_10", "android-37.1"))

        val error = assertThrows<CliError> {
            CloudDevicePicker.pick(Platform.IOS, null, null, Flags.CLI) { mapOf("iPad-Pro" to listOf("iOS-16-2")) }
        }
        assertThat(error.message).contains("lists no iPhone")
    }

    @Test
    fun `only an OS picks the newest phone that offers it, expanding shorthands`() {
        // iPhone-17 has no iOS 18.2; the newest phone that has it.
        assertThat(pick(Platform.IOS, null, "18")).isEqualTo(CloudDevice("iPhone-11", "iOS-18-2"))
        // An exact version beats a newer minor.
        assertThat(pick(Platform.ANDROID, null, "37")).isEqualTo(CloudDevice("pixel_10", "android-37"))
        // A prefixed shorthand only matches its own prefix.
        assertThrows<CliError> { pick(Platform.ANDROID, null, "iOS-34") }

        // Only an iPad offers it: no guessing, ask for the model.
        val error = assertThrows<CliError> { pick(Platform.IOS, null, "iOS-16-4") }
        assertThat(error.message).isEqualTo(
            "No iPhone on Maestro Cloud offers iOS-16-4. Pass --device-model too, or run `maestro list-cloud-devices` to see the available devices."
        )
    }

    @Test
    fun `only a model picks its newest OS, matching the model in any case`() {
        assertThat(pick(Platform.IOS, "iphone-11", null)).isEqualTo(CloudDevice("iPhone-11", "iOS-18-2"))

        val error = assertThrows<CliError> { pick(Platform.IOS, "iPhone-99", null) }
        assertThat(error.message).contains("Pass --device-os too")
    }

    @Test
    fun `model and OS are canonicalized when listed and otherwise sent as given`() {
        assertThat(pick(Platform.IOS, "IPHONE-11", "ios-17-5")).isEqualTo(CloudDevice("iPhone-11", "iOS-17-5"))
        // Hidden devices aren't listed; Cloud validates them. A listed model keeps its listed case.
        assertThat(pick(Platform.IOS, "iPhone-12-mini", "iOS-18-2")).isEqualTo(CloudDevice("iPhone-12-mini", "iOS-18-2"))
        assertThat(pick(Platform.IOS, "iphone-11", "iOS-15-0")).isEqualTo(CloudDevice("iPhone-11", "iOS-15-0"))
        // A failed list fetch doesn't block a fully named device.
        assertThat(CloudDevicePicker.pick(Platform.IOS, "iPhone-11", "iOS-17-5", Flags.CLI) { throw CliError("offline") })
            .isEqualTo(CloudDevice("iPhone-11", "iOS-17-5"))
    }

    @Test
    fun `the list fetch drops models without an OS and turns any failure into a CliError`() {
        val client = mockk<ApiClient>()
        every { client.listCloudDevices() } returns mapOf("ios" to mapOf("iPhone-11" to listOf("iOS-18-2"), "iPhone-99" to emptyList()))
        assertThat(CloudDevicePicker.listedDevices(client, Platform.IOS, Flags.CLI)).isEqualTo(mapOf("iPhone-11" to listOf("iOS-18-2")))

        every { client.listCloudDevices() } throws ApiClient.ApiException(statusCode = 503)
        assertThat(assertThrows<CliError> { CloudDevicePicker.listedDevices(client, Platform.IOS, Flags.MCP) }.message).isEqualTo(
            "Could not get the device list from Maestro Cloud (HTTP 503). Pass device_model and device_os, or try again."
        )

        // Not only HTTP errors: a body that doesn't parse too.
        every { client.listCloudDevices() } throws IllegalArgumentException("bad body")
        assertThrows<CliError> { CloudDevicePicker.listedDevices(client, Platform.IOS, Flags.CLI) }
    }
}
