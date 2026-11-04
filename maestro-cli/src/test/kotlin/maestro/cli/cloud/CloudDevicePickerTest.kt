package maestro.cli.cloud

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import maestro.cli.CliError
import maestro.cli.api.ApiClient
import maestro.device.Platform
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CloudDevicePickerTest {

    @Test
    fun `the list fetch drops models without an OS and turns any failure into a CliError`() {
        val client = mockk<ApiClient>()
        every { client.listCloudDevices() } returns mapOf("ios" to mapOf("iPhone-11" to listOf("iOS-18-2"), "iPhone-99" to emptyList()))
        assertThat(CloudDevicePicker.listedDevices(client, Platform.IOS, CloudDevicePicker.CLI)).isEqualTo(mapOf("iPhone-11" to listOf("iOS-18-2")))

        every { client.listCloudDevices() } throws ApiClient.ApiException(statusCode = 503)
        assertThat(assertThrows<CliError> { CloudDevicePicker.listedDevices(client, Platform.IOS, CloudDevicePicker.MCP) }.message).isEqualTo(
            "Could not get the device list from Maestro Cloud (HTTP 503). Pass device_model and device_os, or try again."
        )

        // Not only HTTP errors: a body that doesn't parse too.
        every { client.listCloudDevices() } throws IllegalArgumentException("bad body")
        assertThrows<CliError> { CloudDevicePicker.listedDevices(client, Platform.IOS, CloudDevicePicker.CLI) }
    }
}
