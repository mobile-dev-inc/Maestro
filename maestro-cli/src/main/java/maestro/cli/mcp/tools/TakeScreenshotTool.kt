package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import maestro.cli.mcp.schema.McpToolInput
import maestro.cli.mcp.schema.McpToolResult
import maestro.cli.mcp.schema.SchemaValidator
import maestro.cli.mcp.schema.ValidationResult
import maestro.cli.session.MaestroSessionManager
import okio.Buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

// Schema definitions for this tool
@Serializable
data class DeviceIdInput(
    val deviceId: String
) : McpToolInput

@Serializable
data class ScreenshotOutput(
    val success: Boolean,
    val deviceId: String,
    val message: String
)

object TakeScreenshotTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "take_screenshot",
                description = "Take a screenshot of the current device screen",
                inputSchema = SchemaValidator.createInputSchema<DeviceIdInput>(),
                outputSchema = SchemaValidator.createOutputSchema<ScreenshotOutput>(),
                annotations = null
            )
        ) { request ->
            // TakeScreenshotTool needs special handling for image content,
            // so we use manual validation but with cleaner error handling
            try {
                val validationResult = SchemaValidator.validateInput(request, DeviceIdInput.serializer())
                when (validationResult) {
                    is ValidationResult.Success -> {
                        val input = validationResult.value
                        val imageData = sessionManager.newSession(
                            host = null,
                            port = null,
                            driverHostPort = null,
                            deviceId = input.deviceId,
                            platform = null
                        ) { session ->
                            val buffer = Buffer()
                            session.maestro.takeScreenshot(buffer, true)
                            val pngBytes = buffer.readByteArray()

                            // Convert PNG to JPEG
                            val pngImage = ImageIO.read(ByteArrayInputStream(pngBytes))
                            val jpegOutput = ByteArrayOutputStream()
                            ImageIO.write(pngImage, "JPEG", jpegOutput)
                            val jpegBytes = jpegOutput.toByteArray()

                            Base64.getEncoder().encodeToString(jpegBytes)
                        }

                        // Create structured output data
                        val structuredResult = ScreenshotOutput(
                            success = true,
                            deviceId = input.deviceId,
                            message = "Screenshot captured successfully"
                        )

                        // Create response with both image and structured content
                        CallToolResult(
                            content = listOf(
                                ImageContent(data = imageData, mimeType = "image/jpeg"),
                                TextContent(Json.encodeToString(ScreenshotOutput.serializer(), structuredResult))
                            ),
                            structuredContent = Json.encodeToJsonElement(ScreenshotOutput.serializer(), structuredResult) as JsonObject,
                            isError = false
                        )
                    }
                    is ValidationResult.Error -> {
                        throw Exception(validationResult.message)
                    }
                }
            } catch (e: Exception) {
                SchemaValidator.createErrorResult(
                    McpToolResult.Error("Failed to take screenshot: ${e.message}")
                )
            }
        }
    }
}