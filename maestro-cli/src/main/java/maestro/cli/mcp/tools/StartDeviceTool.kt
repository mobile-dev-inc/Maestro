package maestro.cli.mcp.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerializationException
import maestro.device.DeviceService
import maestro.device.Platform
import maestro.cli.mcp.McpTool
import maestro.cli.mcp.ToolCallResult
import maestro.cli.mcp.JsonSchemaField

@Serializable
data class StartDeviceParams(
    @JsonSchemaField(description = "ID of the device to start (from list_devices). Optional.")
    val device_id: String? = null,
    
    @JsonSchemaField(description = "Platform to start (ios or android). Optional. Default: ios.")
    val platform: String? = null
)

@Serializable
data class StartDeviceResult(
    val device_id: String,
    val name: String,
    val platform: String,
    val type: String,
    val already_running: Boolean? = null
)

class StartDeviceTool : McpTool() {
    companion object {
        private const val DEFAULT_PLATFORM = "ios"
    }

    override val definition = generateToolDefinition<StartDeviceParams>(
        "start_device",
        "Start a device (simulator/emulator) and return its device ID. " +
            "You must provide either a device_id (from list_devices) or a platform (ios or android). " +
            "If device_id is provided, starts that device. If platform is provided, starts any available device for that platform. " +
            "If neither is provided, defaults to platform = ios. This is more flexible than the default Maestro CLI behavior, which fails if the requested runtime is not installed."
    )

    override suspend fun execute(arguments: JsonElement): ToolCallResult {
        return safeExecute {
            val params = try {
                deserializeJsonParams<StartDeviceParams>(arguments)
            } catch (e: SerializationException) {
                return@safeExecute errorResponse("Invalid parameters: ${e.message?.substringAfter(":")?.trim()}")
            }
            
            val deviceId = params.device_id
            val platformStr = params.platform ?: DEFAULT_PLATFORM

            // Get all connected and available devices
            val availableDevices = DeviceService.listAvailableForLaunchDevices(includeWeb = true)
            val connectedDevices = DeviceService.listConnectedDevices()

            // ---
            // Logic: For both device_id and platform, first check for a matching connected device.
            // If found, return it as already_running. If not, look for an available device to launch.
            // This ensures idempotency and intuitive behavior.
            // ---

            // Helper to build result
            fun buildResult(device: maestro.device.Device.Connected, alreadyRunning: Boolean): StartDeviceResult {
                return StartDeviceResult(
                    device_id = device.instanceId,
                    name = device.description,
                    platform = device.platform.name.lowercase(),
                    type = device.deviceType.name.lowercase(),
                    already_running = alreadyRunning
                )
            }

            if (deviceId != null) {
                // 1. Check for a connected device with this instanceId
                val connected = connectedDevices.find { it.instanceId == deviceId }
                if (connected != null) {
                    return@safeExecute successResponse(buildResult(connected, true))
                }
                // 2. Check for an available device with this modelId
                val available = availableDevices.find { it.modelId == deviceId }
                if (available != null) {
                    val connectedDevice = DeviceService.startDevice(
                        device = available,
                        driverHostPort = null
                    )
                    return@safeExecute successResponse(buildResult(connectedDevice, false))
                }
                return@safeExecute errorResponse("No device found with device_id: $deviceId")
            }

            // No device_id provided: use platform
            val platform = Platform.fromString(platformStr) ?: Platform.IOS
            // 1. Check for a connected device matching the platform
            val connected = connectedDevices.find { it.platform == platform }
            if (connected != null) {
                return@safeExecute successResponse(buildResult(connected, true))
            }
            // 2. Check for an available device matching the platform
            val available = availableDevices.find { it.platform == platform }
            if (available != null) {
                val connectedDevice = DeviceService.startDevice(
                    device = available,
                    driverHostPort = null
                )
                return@safeExecute successResponse(buildResult(connectedDevice, false))
            }
            return@safeExecute errorResponse("No available or connected device found for platform: $platformStr")
        }
    }
} 