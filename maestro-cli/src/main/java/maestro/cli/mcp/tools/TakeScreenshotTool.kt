package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import maestro.cli.mcp.McpMaestroSessionManager
import okio.Buffer

object TakeScreenshotTool {

    internal fun create(sessionManager: McpMaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "take_screenshot",
                description = "Take a screenshot of the current device screen",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to take a screenshot from")
                        }
                    },
                    required = listOf("device_id")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments?.get("device_id")?.jsonPrimitive?.content

                if (deviceId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("device_id is required")),
                        isError = true
                    )
                }

                val imageContent = sessionManager.withSession(
                    deviceId = deviceId,
                ) { session ->
                    val buffer = Buffer()
                    runBlocking { session.maestro.takeScreenshot(buffer, true) }
                    McpImages.screenshotContent(buffer.readByteArray())
                }

                CallToolResult(content = listOf(imageContent))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to take screenshot: ${e::class.simpleName}: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}
