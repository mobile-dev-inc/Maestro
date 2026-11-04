package maestro.cli.cloud

import maestro.cli.CliError
import maestro.cli.api.ApiClient
import maestro.cli.device.DevicePicker
import maestro.device.Platform

/** The Maestro Cloud side of [DevicePicker]: its device list, and how each caller names the options. */
object CloudDevicePicker {

    private const val LIST_HINT = "run `maestro list-cloud-devices` to see the available devices."

    val CLI = DevicePicker.Source("Maestro Cloud", "--device-model", "--device-os", LIST_HINT)
    val MCP = DevicePicker.Source("Maestro Cloud", "device_model", "device_os", LIST_HINT)

    /** `/v2/device/list` for [platform]: model to OS ids, without models that offer no OS. */
    fun listedDevices(client: ApiClient, platform: Platform, source: DevicePicker.Source): Map<String, List<String>> = try {
        client.listCloudDevices()[platform.name.lowercase()].orEmpty().filterValues { it.isNotEmpty() }
    } catch (e: Exception) {
        val reason = (e as? ApiClient.ApiException)?.let { api -> api.statusCode?.let { "HTTP $it" } ?: "network error" }
            ?: e.javaClass.simpleName
        throw CliError("Could not get the device list from Maestro Cloud ($reason). Pass ${source.modelFlag} and ${source.osFlag}, or try again.")
    }
}
