package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.Maestro
import okio.Buffer
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

object TakeScreenshotTool {
    fun create(maestro: Maestro): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "take_screenshot",
                description = "Take a screenshot of the current device screen",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {},
                    required = emptyList()
                )
            )
        ) { request ->
            try {
                val buffer = Buffer()
                maestro.takeScreenshot(buffer, true)
                val pngBytes = buffer.readByteArray()

                // Convert PNG to JPEG
                val pngImage = ImageIO.read(ByteArrayInputStream(pngBytes))
                val jpegOutput = ByteArrayOutputStream()
                ImageIO.write(pngImage, "JPEG", jpegOutput)
                val jpegBytes = jpegOutput.toByteArray()

                val base64 = Base64.getEncoder().encodeToString(jpegBytes)

                CallToolResult(content = listOf(ImageContent(data = base64, mimeType = "image/jpeg")))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to take screenshot: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}
