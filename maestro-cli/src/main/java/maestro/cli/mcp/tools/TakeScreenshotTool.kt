package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import maestro.cli.mcp.McpMaestroSessionManager
import okio.Buffer
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

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
                
                val result = sessionManager.withSession(
                    deviceId = deviceId,
                ) { session ->
                    val buffer = Buffer()
                    runBlocking { session.maestro.takeScreenshot(buffer, true) }
                    val pngBytes = buffer.readByteArray()
                    
                    // Convert PNG to JPEG
                    val pngImage = ImageIO.read(ByteArrayInputStream(pngBytes))
                    val jpegOutput = ByteArrayOutputStream()
                    ImageIO.write(pngImage, "JPEG", jpegOutput)
                    val jpegBytes = jpegOutput.toByteArray()
                    
                    val base64 = Base64.getEncoder().encodeToString(jpegBytes)
                    base64
                }
                
                val imageContent = ImageContent(
                    data = result,
                    mimeType = "image/jpeg"
                )
                
                CallToolResult(content = listOf(imageContent))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to take screenshot: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}
