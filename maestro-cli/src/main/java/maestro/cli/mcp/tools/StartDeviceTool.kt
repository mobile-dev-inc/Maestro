package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.device.DeviceService
import maestro.device.Platform

object StartDeviceTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "start_device",
                description = "Start a device (simulator/emulator) and return its device ID. " +
                    "You must provide either a device_id (from list_devices) or a platform (ios or android). " +
                    "If device_id is provided, starts that device. If platform is provided, starts any available device for that platform. " +
                    "If neither is provided, defaults to platform = ios.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "ID of the device to start (from list_devices). Optional.")
                        }
                        putJsonObject("platform") {
                            put("type", "string") 
                            put("description", "Platform to start (ios or android). Optional. Default: ios.")
                        }
                    },
                    required = emptyList()
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val platformStr = request.arguments["platform"]?.jsonPrimitive?.content ?: "ios"
                
                // Get all connected and available devices
                val availableDevices = DeviceService.listAvailableForLaunchDevices(includeWeb = true)
                val connectedDevices = DeviceService.listConnectedDevices()

                // Helper to build result
                fun buildResult(device: maestro.device.Device.Connected, alreadyRunning: Boolean): String {
                    return buildJsonObject {
                        put("device_id", device.instanceId)
                        put("name", device.description)
                        put("platform", device.platform.name.lowercase())
                        put("type", device.deviceType.name.lowercase())
                        put("already_running", alreadyRunning)
                    }.toString()
                }

                if (deviceId != null) {
                    // Check for a connected device with this instanceId
                    val connected = connectedDevices.find { it.instanceId == deviceId }
                    if (connected != null) {
                        return@RegisteredTool CallToolResult(content = listOf(TextContent(buildResult(connected, true))))
                    }
                    // Check for an available device with this modelId
                    val available = availableDevices.find { it.modelId == deviceId }
                    if (available != null) {
                        val connectedDevice = DeviceService.startDevice(
                            device = available,
                            driverHostPort = null
                        )

                        // For Android devices, initialize driver to install APKs and start instrumentation
                        if (connectedDevice.platform == Platform.ANDROID) {
                            sessionManager.newSession(
                                host = null,
                                port = null,
                                driverHostPort = null,
                                deviceId = connectedDevice.instanceId,
                                platform = "android"
                            ) { _ ->
                                // Session initialization installs driver APKs and starts instrumentation
                                // The session is then closed but the driver remains running
                            }
                        }

                        return@RegisteredTool CallToolResult(content = listOf(TextContent(buildResult(connectedDevice, false))))
                    }
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("No device found with device_id: $deviceId")),
                        isError = true
                    )
                }

                // No device_id provided: use platform
                val platform = Platform.fromString(platformStr) ?: Platform.IOS
                // Check for a connected device matching the platform
                val connected = connectedDevices.find { it.platform == platform }
                if (connected != null) {
                    return@RegisteredTool CallToolResult(content = listOf(TextContent(buildResult(connected, true))))
                }
                // Check for an available device matching the platform
                val available = availableDevices.find { it.platform == platform }
                if (available != null) {
                    val connectedDevice = DeviceService.startDevice(
                        device = available,
                        driverHostPort = null
                    )

                    // For Android devices, initialize driver to install APKs and start instrumentation
                    if (connectedDevice.platform == Platform.ANDROID) {
                        sessionManager.newSession(
                            host = null,
                            port = null,
                            driverHostPort = null,
                            deviceId = connectedDevice.instanceId,
                            platform = "android"
                        ) { _ ->
                            // Session initialization installs driver APKs and starts instrumentation
                            // The session is then closed but the driver remains running
                        }
                    }

                    return@RegisteredTool CallToolResult(content = listOf(TextContent(buildResult(connectedDevice, false))))
                }
                return@RegisteredTool CallToolResult(
                    content = listOf(TextContent("No available or connected device found for platform: $platformStr")),
                    isError = true
                )
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to start device: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}