package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import maestro.cli.mcp.MaestroTool
import maestro.cli.mcp.schema.McpToolInput
import maestro.device.DeviceService

// Schema definitions for this tool
@Serializable
data class EmptyInput(
    // No fields needed for empty input
    val dummy: String? = null
) : McpToolInput

@Serializable
data class DeviceInfo(
    val deviceId: String,
    val name: String,
    val platform: String,
    val type: String,
    val connected: Boolean,
    val model: String? = null,
    val osVersion: String? = null,
    val resolution: String? = null
)

@Serializable
data class DeviceListOutput(
    val devices: List<DeviceInfo>
)

object ListDevicesTool {
    fun create(): RegisteredTool {
        return MaestroTool.create<EmptyInput, DeviceListOutput>(
            name = "list_devices",
            description = "List all available devices that can be launched for automation."
        ) { input ->
            val availableDevices = DeviceService.listAvailableForLaunchDevices(includeWeb = true)
            val connectedDevices = DeviceService.listConnectedDevices()
            
            val allDevices = mutableListOf<DeviceInfo>()
            
            // Add connected devices
            connectedDevices.forEach { device ->
                allDevices.add(DeviceInfo(
                    deviceId = device.instanceId,
                    name = device.description,
                    platform = device.platform.name.lowercase(),
                    type = device.deviceType.name.lowercase(),
                    connected = true
                ))
            }
            
            // Add available devices that aren't already connected
            availableDevices.forEach { device ->
                val alreadyConnected = connectedDevices.any { it.instanceId == device.modelId }
                if (!alreadyConnected) {
                    allDevices.add(DeviceInfo(
                        deviceId = device.modelId,
                        name = device.description,
                        platform = device.platform.name.lowercase(),
                        type = device.deviceType.name.lowercase(),
                        connected = false
                    ))
                }
            }
            
            DeviceListOutput(devices = allDevices)
        }
    }
}