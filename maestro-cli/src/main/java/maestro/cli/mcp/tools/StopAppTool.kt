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
                            put("description", "Bundle ID or app ID to stop (supports environment variable substitution like \${APP_ID})")
                        }
                        putJsonObject("env") {
                            put("type", "object")
                            put("description", "Optional environment variables for app ID substitution (e.g., {\"APP_ID\": \"com.example.app\"})")
                            putJsonObject("additionalProperties") {
                                put("type", "string")
                            }
                        }
                    },
                    required = listOf("device_id", "appId")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val appId = request.arguments["appId"]?.jsonPrimitive?.content
                val envParam = request.arguments["env"]?.jsonObject
                
                if (deviceId == null || appId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("Both device_id and appId are required")),
                        isError = true
                    )
                }
                
                // Parse environment variables and substitute in app ID
                val env = envParam?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                val resolvedAppId = env.entries.fold(appId) { acc, (key, value) ->
                    acc.replace("\${$key}", value)
                }
                
                val result = sessionManager.newSession(
                    host = null,
                    port = null,
                    driverHostPort = MaestroSessionManager.MCP_DRIVER_PORT,
                    deviceId = deviceId,
                    platform = null
                ) { session ->
                    val command = StopAppCommand(
                        appId = resolvedAppId,
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
                        put("app_id", resolvedAppId)
                        put("message", "App stopped successfully")
                        if (env.isNotEmpty()) {
                            putJsonObject("env_vars") {
                                env.forEach { (key, value) ->
                                    put(key, value)
                                }
                            }
                        }
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