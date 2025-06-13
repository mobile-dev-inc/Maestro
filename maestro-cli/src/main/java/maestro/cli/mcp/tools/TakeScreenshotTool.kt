package maestro.cli.mcp.tools

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.SerializationException
import maestro.cli.mcp.McpTool
import maestro.cli.mcp.ToolCallResult
import maestro.cli.mcp.ImageContent
import maestro.cli.session.MaestroSessionManager
import okio.Buffer
import java.util.Base64
import kotlinx.serialization.Serializable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import maestro.cli.mcp.JsonSchemaField

@Serializable
data class TakeScreenshotParams(
    @JsonSchemaField(description = "The ID of the device to take a screenshot from", required = true)
    val device_id: String
)

/**
 * Tool to take a screenshot of the current device screen
 */
class TakeScreenshotTool(private val sessionManager: MaestroSessionManager) : McpTool() {
    override val definition = generateToolDefinition<TakeScreenshotParams>(
        "take_screenshot",
        "Take a screenshot of the current device screen."
    )

    @Serializable
    data class ScreenshotResult(
        val image: String,
        val mime_type: String
    )

    override suspend fun execute(arguments: JsonElement): ToolCallResult {
        return safeExecute {
            val params = try {
                deserializeJsonParams<TakeScreenshotParams>(arguments)
            } catch (e: SerializationException) {
                return@safeExecute errorResponse("Invalid parameters: ${e.message?.substringAfter(":")?.trim()}")
            }
            
            val deviceId = params.device_id
            val session = MaestroSessionManager.newSession(
                host = null,
                port = null,
                driverHostPort = null,
                deviceId = deviceId,
                platform = null
            ) { it }
            val buffer = Buffer()
            session.maestro.takeScreenshot(buffer, true)
            val pngBytes = buffer.readByteArray()
            
            // Convert PNG to JPEG
            val pngImage = ImageIO.read(ByteArrayInputStream(pngBytes))
            val jpegOutput = ByteArrayOutputStream()
            ImageIO.write(pngImage, "JPEG", jpegOutput)
            val jpegBytes = jpegOutput.toByteArray()
            
            val base64 = Base64.getEncoder().encodeToString(jpegBytes)
            val imageContent = ImageContent(
                data = base64,
                mimeType = "image/jpeg"
            )
            successResponse(listOf(imageContent))
        }
    }
}
