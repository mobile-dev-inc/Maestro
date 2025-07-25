package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import maestro.cli.mcp.MaestroTool
import maestro.cli.mcp.schema.McpToolInput
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra

// Schema definitions for this tool
@Serializable
data class LaunchAppInput(
    val deviceId: String,
    val appId: String,
    val clearState: Boolean? = null,
    val clearKeychain: Boolean? = null,
    val stopApp: Boolean? = null,
    val permissions: Map<String, String>? = null,
    val launchArguments: Map<String, String>? = null
) : McpToolInput

@Serializable
data class LaunchAppOutput(
    val success: Boolean,
    val deviceId: String,
    val appId: String,
    val message: String
)

object LaunchAppTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return MaestroTool.create<LaunchAppInput, LaunchAppOutput>(
            name = "launch_app",
            description = "Launch an application on the connected device"
        ) { input ->
            sessionManager.newSession(
                host = null,
                port = null,
                driverHostPort = null,
                deviceId = input.deviceId,
                platform = null
            ) { session ->
                val command = LaunchAppCommand(
                    appId = input.appId,
                    clearState = input.clearState,
                    clearKeychain = input.clearKeychain,
                    stopApp = input.stopApp,
                    permissions = input.permissions,
                    launchArguments = input.launchArguments,
                    label = null,
                    optional = false
                )
                
                val orchestra = Orchestra(session.maestro)
                runBlocking {
                    orchestra.executeCommands(listOf(MaestroCommand(command = command)))
                }
                
                LaunchAppOutput(
                    success = true,
                    deviceId = input.deviceId,
                    appId = input.appId,
                    message = "App launched successfully"
                )
            }
        }
    }
}