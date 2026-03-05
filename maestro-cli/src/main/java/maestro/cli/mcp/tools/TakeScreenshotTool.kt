package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import okio.Buffer
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

object TakeScreenshotTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
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
                        putJsonObject("maxDimensions") {
                            put("type", "integer")
                            put("description", "Maximum size (in pixels) for the longest dimension of the screenshot. The image will be scaled down proportionally if either dimension exceeds this value. Note: Claude works best with images below 2000 pixels.")
                        }
                    },
                    required = listOf("device_id")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments?.get("device_id")?.jsonPrimitive?.content
                val maxDimensions = request.arguments?.get("maxDimensions")?.jsonPrimitive?.intOrNull
                
                if (deviceId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("device_id is required")),
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
                    val buffer = Buffer()
                    runBlocking { session.maestro.takeScreenshot(buffer, true) }
                    val pngBytes = buffer.readByteArray()
                    
                    // Convert PNG to JPEG
                    val pngImage = ImageIO.read(ByteArrayInputStream(pngBytes))
                    val imageToEncode = if (maxDimensions != null && maxOf(pngImage.width, pngImage.height) > maxDimensions) {
                        val scale = maxDimensions.toDouble() / maxOf(pngImage.width, pngImage.height)
                        val newWidth = (pngImage.width * scale).toInt()
                        val newHeight = (pngImage.height * scale).toInt()
                        val scaled = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
                        val g2d = scaled.createGraphics()
                        g2d.drawImage(pngImage.getScaledInstance(newWidth, newHeight, java.awt.Image.SCALE_SMOOTH), 0, 0, null)
                        g2d.dispose()
                        scaled
                    } else {
                        pngImage
                    }
                    val jpegOutput = ByteArrayOutputStream()
                    ImageIO.write(imageToEncode, "JPEG", jpegOutput)
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