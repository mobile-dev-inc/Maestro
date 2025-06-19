package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.device.DeviceService

object ListDevicesTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "list_devices",
                description = "List all available devices that can be used for automation",
                inputSchema = Tool.Input(
                    properties = buildJsonObject { },
                    required = emptyList()
                )
            )
        ) { _ ->
            try {
                val availableDevices = DeviceService.listAvailableForLaunchDevices(includeWeb = true)
                val connectedDevices = DeviceService.listConnectedDevices()
                
                val allDevices = buildJsonArray {
                    // Add connected devices
                    connectedDevices.forEach { device ->
                        addJsonObject {
                            put("device_id", device.instanceId)
                            put("name", device.description)
                            put("platform", device.platform.name.lowercase())
                            put("type", device.deviceType.name.lowercase())
                            put("connected", true)
                        }
                    }
                    
                    // Add available devices that aren't already connected
                    availableDevices.forEach { device ->
                        val alreadyConnected = connectedDevices.any { it.instanceId == device.modelId }
                        if (!alreadyConnected) {
                            addJsonObject {
                                put("device_id", device.modelId)
                                put("name", device.description)
                                put("platform", device.platform.name.lowercase())
                                put("type", device.deviceType.name.lowercase()) 
                                put("connected", false)
                            }
                        }
                    }
                }
                
                val result = buildJsonObject {
                    put("devices", allDevices)
                }
                
                CallToolResult(content = listOf(TextContent(result.toString())))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to list devices: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}