package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import maestro.cli.mcp.MaestroTool
import maestro.cli.mcp.schema.McpToolInput
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.StopAppCommand

// Schema definitions for this tool
@Serializable
data class StopAppInput(
    val deviceId: String,
    val appId: String
) : McpToolInput

@Serializable
data class StopAppOutput(
    val success: Boolean,
    val deviceId: String,
    val appId: String,
    val message: String
)

object StopAppTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return MaestroTool.create<StopAppInput, StopAppOutput>(
            name = "stop_app",
            description = "Stop an application on the connected device"
        ) { input ->
            sessionManager.newSession(
                host = null,
                port = null,
                driverHostPort = null,
                deviceId = input.deviceId,
                platform = null
            ) { session ->
                val command = StopAppCommand(
                    appId = input.appId,
                    label = null,
                    optional = false
                )
                
                val orchestra = Orchestra(session.maestro)
                runBlocking {
                    orchestra.executeCommands(listOf(MaestroCommand(command = command)))
                }
                
                StopAppOutput(
                    success = true,
                    deviceId = input.deviceId,
                    appId = input.appId,
                    message = "App stopped successfully"
                )
            }
        }
    }
}