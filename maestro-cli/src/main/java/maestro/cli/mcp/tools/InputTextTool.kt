package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.InputTextCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.MaestroCommand
import kotlinx.coroutines.runBlocking

object InputTextTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "input_text",
                description = "Input text into the currently focused text field",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to input text on")
                        }
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "The text to input")
                        }
                    },
                    required = listOf("device_id", "text")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val text = request.arguments["text"]?.jsonPrimitive?.content
                
                if (deviceId == null || text == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("Both device_id and text are required")),
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
                    val command = InputTextCommand(
                        text = text,
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
                        put("text", text)
                        put("message", "Text input successful")
                    }.toString()
                }
                
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to input text: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}