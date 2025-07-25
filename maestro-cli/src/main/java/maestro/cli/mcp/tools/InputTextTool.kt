package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import maestro.cli.mcp.MaestroTool
import maestro.cli.mcp.schema.McpToolInput
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.InputTextCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra

// Schema definitions for this tool
@Serializable
data class InputTextInput(
    val deviceId: String,
    val text: String
) : McpToolInput

@Serializable
data class InputTextOutput(
    val success: Boolean,
    val deviceId: String,
    val text: String,
    val message: String
)

object InputTextTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return MaestroTool.create<InputTextInput, InputTextOutput>(
            name = "input_text",
            description = "Input text into the currently focused text field"
        ) { input ->
            sessionManager.newSession(
                host = null,
                port = null,
                driverHostPort = null,
                deviceId = input.deviceId,
                platform = null
            ) { session ->
                val command = InputTextCommand(
                    text = input.text,
                    label = null,
                    optional = false
                )
                
                val orchestra = Orchestra(session.maestro)
                runBlocking {
                    orchestra.executeCommands(listOf(MaestroCommand(command = command)))
                }
                
                InputTextOutput(
                    success = true,
                    deviceId = input.deviceId,
                    text = input.text,
                    message = "Text input successful"
                )
            }
        }
    }
}