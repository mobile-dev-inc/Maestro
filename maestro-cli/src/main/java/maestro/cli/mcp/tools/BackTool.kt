package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import maestro.cli.mcp.MaestroTool
import maestro.cli.mcp.schema.McpToolInput
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.BackPressCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.MaestroCommand
import kotlinx.coroutines.runBlocking

// Schema definitions for this tool
@Serializable
data class BackInput(
    val deviceId: String
) : McpToolInput

@Serializable
data class BackOutput(
    val success: Boolean,
    val deviceId: String,
    val message: String
)

object BackTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return MaestroTool.create<BackInput, BackOutput>(
            name = "back",
            description = "Press the back button on the device"
        ) { input ->
            sessionManager.newSession(
                host = null,
                port = null,
                driverHostPort = null,
                deviceId = input.deviceId,
                platform = null
            ) { session ->
                val command = BackPressCommand(
                    label = null,
                    optional = false
                )
                
                val orchestra = Orchestra(session.maestro)
                runBlocking {
                    orchestra.executeCommands(listOf(MaestroCommand(command = command)))
                }
                
                BackOutput(
                    success = true,
                    deviceId = input.deviceId,
                    message = "Back button pressed successfully"
                )
            }
        }
    }
}