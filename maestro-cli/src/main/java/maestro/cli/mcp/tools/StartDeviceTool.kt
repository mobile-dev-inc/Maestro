package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import maestro.cli.mcp.MaestroTool
import maestro.cli.mcp.schema.McpToolInput
import maestro.device.DeviceService
import maestro.device.Platform

// Schema definitions for this tool
@Serializable
data class StartDeviceInput(
    val deviceId: String? = null,
    val platform: String = "ios"
) : McpToolInput

@Serializable
data class StartDeviceOutput(
    val deviceId: String,
    val name: String,
    val platform: String,
    val type: String,
    val alreadyRunning: Boolean
)

object StartDeviceTool {
    fun create(): RegisteredTool {
        return MaestroTool.create<StartDeviceInput, StartDeviceOutput>(
            name = "start_device",
            description = "Start a device (simulator/emulator) and return its device ID. " +
                "You must provide either a device_id (from list_devices) or a platform (ios or android). " +
                "If device_id is provided, starts that device. If platform is provided, starts any available device for that platform. " +
                "If neither is provided, defaults to platform = ios."
        ) { input ->
            // Get all connected and available devices
            val availableDevices = DeviceService.listAvailableForLaunchDevices(includeWeb = true)
            val connectedDevices = DeviceService.listConnectedDevices()

            // Helper to create structured result
            fun createResult(device: maestro.device.Device.Connected, alreadyRunning: Boolean): StartDeviceOutput {
                return StartDeviceOutput(
                    deviceId = device.instanceId,
                    name = device.description,
                    platform = device.platform.name.lowercase(),
                    type = device.deviceType.name.lowercase(),
                    alreadyRunning = alreadyRunning
                )
            }

            if (input.deviceId != null) {
                // Check for a connected device with this instanceId
                val connected = connectedDevices.find { it.instanceId == input.deviceId }
                if (connected != null) {
                    return@create createResult(connected, true)
                }
                // Check for an available device with this modelId
                val available = availableDevices.find { it.modelId == input.deviceId }
                if (available != null) {
                    val connectedDevice = DeviceService.startDevice(
                        device = available,
                        driverHostPort = null
                    )
                    return@create createResult(connectedDevice, false)
                }
                throw Exception("No device found with device_id: ${input.deviceId}")
            }

            // No device_id provided: use platform
            val platform = Platform.fromString(input.platform) ?: Platform.IOS
            // Check for a connected device matching the platform
            val connected = connectedDevices.find { it.platform == platform }
            if (connected != null) {
                return@create createResult(connected, true)
            }
            // Check for an available device matching the platform
            val available = availableDevices.find { it.platform == platform }
            if (available != null) {
                val connectedDevice = DeviceService.startDevice(
                    device = available,
                    driverHostPort = null
                )
                return@create createResult(connectedDevice, false)
            }
            throw Exception("No available or connected device found for platform: ${input.platform}")
        }
    }
}