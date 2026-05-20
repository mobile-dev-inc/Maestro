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
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

object TakeScreenshotTool {
    // Vision models (e.g. Claude) reject images whose longest side exceeds 2000px,
    // so screenshots are downscaled to this limit before being returned. See issue #2952.
    private const val MAX_IMAGE_DIMENSION = 2000
    private const val JPEG_QUALITY = 0.9f

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

                    // Convert PNG to JPEG, downscaling so the longest side stays within
                    // MAX_IMAGE_DIMENSION. Drawing onto an RGB canvas applies the scale and
                    // flattens any alpha channel, which JPEG cannot represent.
                    val pngImage = ImageIO.read(ByteArrayInputStream(pngBytes))
                        ?: error("Failed to decode screenshot image")

                    val longestSide = maxOf(pngImage.width, pngImage.height)
                    val scale = if (longestSide > MAX_IMAGE_DIMENSION) {
                        MAX_IMAGE_DIMENSION.toDouble() / longestSide
                    } else {
                        1.0
                    }
                    val targetWidth = (pngImage.width * scale).toInt().coerceAtLeast(1)
                    val targetHeight = (pngImage.height * scale).toInt().coerceAtLeast(1)

                    val jpegImage = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB)
                    val graphics = jpegImage.createGraphics()
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
                    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    graphics.drawImage(pngImage, 0, 0, targetWidth, targetHeight, null)
                    graphics.dispose()

                    val jpegOutput = ByteArrayOutputStream()
                    val writer = ImageIO.getImageWritersByFormatName("JPEG").next()
                    val params = writer.defaultWriteParam.apply {
                        compressionMode = ImageWriteParam.MODE_EXPLICIT
                        compressionQuality = JPEG_QUALITY
                    }
                    ImageIO.createImageOutputStream(jpegOutput).use { imageOutputStream ->
                        writer.output = imageOutputStream
                        writer.write(null, IIOImage(jpegImage, null, null), params)
                    }
                    writer.dispose()

                    Base64.getEncoder().encodeToString(jpegOutput.toByteArray())
                }

                val imageContent = ImageContent(
                    data = result,
                    mimeType = "image/jpeg"
                )

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
