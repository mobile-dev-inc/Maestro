package maestro.cli.mcp.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import maestro.device.DeviceService
import maestro.cli.mcp.McpTool
import maestro.cli.mcp.ToolCallResult
import maestro.cli.session.MaestroSessionManager

// Empty params class for tools with no parameters
@Serializable
class EmptyParams

@Serializable
data class DeviceInfo(
    val device_id: String,
    val name: String,
    val platform: String,
    val type: String,
    val connected: Boolean
)

@Serializable
data class ListDevicesResult(val devices: List<DeviceInfo>)

class ListDevicesTool(
    private val sessionManager: MaestroSessionManager
) : McpTool() {
    override val definition = generateToolDefinition<EmptyParams>(
        "list_devices",
        "List all devices, both connected and available for launch, with a connected flag."
    )

    override suspend fun execute(arguments: JsonElement): ToolCallResult {
        return safeExecute {
            val connectedDevices = DeviceService.listConnectedDevices(includeWeb = true)
            val availableDevices = DeviceService.listAvailableForLaunchDevices(includeWeb = true)

            val connectedSet = connectedDevices.map { it.instanceId }.toSet()

            val devices = mutableListOf<DeviceInfo>()
            devices += connectedDevices.map { device ->
                DeviceInfo(
                    device_id = device.instanceId,
                    name = device.description,
                    platform = device.platform.name.lowercase(),
                    type = device.deviceType.name.lowercase(),
                    connected = true
                )
            }
            devices += availableDevices.filter { it.modelId !in connectedSet }.map { device ->
                DeviceInfo(
                    device_id = device.modelId,
                    name = device.description,
                    platform = device.platform.name.lowercase(),
                    type = device.deviceType.name.lowercase(),
                    connected = false
                )
            }
            val result = ListDevicesResult(devices)
            successResponse(result)
        }
    }
}