package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.StopAppCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.MaestroCommand
import kotlinx.coroutines.runBlocking

object StopAppTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "stop_app",
                description = "Stop an application on the connected device",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to stop the app on")
                        }
                        putJsonObject("appId") {
                            put("type", "string")
                            put("description", "Bundle ID or app ID to stop")
                        }
                    },
                    required = listOf("device_id", "appId")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val appId = request.arguments["appId"]?.jsonPrimitive?.content
                
                if (deviceId == null || appId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("Both device_id and appId are required")),
                        isError = true
                    )
                }
                
                val result = sessionManager.newSession(
                    host = null,
                    port = null,
                    driverHostPort = null,
                    deviceId = deviceId,
                    platform = null
                ) { session ->
                    val command = StopAppCommand(
                        appId = appId,
                        label = null,
                        optional = false
                    )
                    
                    val orchestra = Orchestra(session.maestro)
                    runBlocking {
                        orchestra.executeCommands(listOf(MaestroCommand(command = command)))
                    }
                    
                    buildJsonObject {
                        put("success", true)
                        put("device_id", deviceId)
                        put("app_id", appId)
                        put("message", "App stopped successfully")
                    }.toString()
                }
                
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to stop app: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}