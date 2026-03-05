package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import okio.Buffer
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object TakeScreenshotTool {
    private const val DEFAULT_MAX_DIMENSIONS = 2000
    private const val JPEG_QUALITY = 0.9f

    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "take_screenshot",
                description = "Take a screenshot of the current device screen",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to take a screenshot from")
                        }
                        putJsonObject("maxDimensions") {
                            put("type", "integer")
                            put("description", "Maximum size (in pixels) for the longest dimension of the screenshot. The image will be scaled down proportionally if either dimension exceeds this value. Defaults to 2000. Note: Claude works best with images below 2000 pixels.")
                            put("minimum", 1)
                        }
                    },
                    required = listOf("device_id")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                if (deviceId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("device_id is required")),
                        isError = true
                    )
                }

                val maxDimensions = (request.arguments["maxDimensions"]?.jsonPrimitive?.intOrNull
                    ?: DEFAULT_MAX_DIMENSIONS).coerceAtLeast(1)

                val result = sessionManager.newSession(
                    host = null,
                    port = null,
                    driverHostPort = null,
                    deviceId = deviceId,
                    platform = null
                ) { session ->
                    val buffer = Buffer()
                    session.maestro.takeScreenshot(buffer, true)
                    val pngBytes = buffer.readByteArray()

                    val pngImage = ImageIO.read(ByteArrayInputStream(pngBytes))
                        ?: error("Failed to decode screenshot image")

                    val longestSide = maxOf(pngImage.width, pngImage.height)
                    val imageToEncode = if (longestSide > maxDimensions) {
                        val scale = maxDimensions.toDouble() / longestSide
                        val newWidth = (pngImage.width * scale).toInt()
                        val newHeight = (pngImage.height * scale).toInt()
                        val scaled = BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
                        val g2d = scaled.createGraphics()
                        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                        g2d.drawImage(pngImage, 0, 0, newWidth, newHeight, null)
                        g2d.dispose()
                        scaled
                    } else {
                        pngImage
                    }

                    val jpegOutput = ByteArrayOutputStream()
                    val writer = ImageIO.getImageWritersByFormatName("JPEG").next()
                    val params = writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = JPEG_QUALITY
                    }
                    ImageIO.createImageOutputStream(jpegOutput).use { imageOutputStream ->
                        writer.output = imageOutputStream
                        writer.write(null, IIOImage(imageToEncode, null, null), params)
                    }
                    writer.dispose()

                    Base64.getEncoder().encodeToString(jpegOutput.toByteArray())
                }

                CallToolResult(content = listOf(ImageContent(data = result, mimeType = "image/jpeg")))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to take screenshot: ${e::class.simpleName}: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}
