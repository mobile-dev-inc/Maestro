package maestro.cli.mcp.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.LaunchAppCommand
import maestro.cli.mcp.McpCommandTool
import maestro.cli.mcp.JsonSchemaField

/**
 * Parameters for launching an app - auto-generated JSON Schema from field annotations
 */
@Serializable
data class LaunchAppParams(
    @JsonSchemaField(description = "The ID of the device to launch the app on", required = true)
    val device_id: String,
    
    @JsonSchemaField(description = "Bundle ID or app ID to launch", required = true)
    val appId: String
)

/**
 * Tool to launch an app using direct LaunchAppCommand construction
 */
class LaunchAppTool(sessionManager: MaestroSessionManager) : 
    McpCommandTool<LaunchAppParams, LaunchAppCommand>(sessionManager) {

    override val toolName = "launch_app"
    override val toolDescription = "Launch an application on the connected device"

    override val definition = generateToolDefinition<LaunchAppParams>(toolName, toolDescription)

    override fun deserializeParams(json: JsonElement) = deserializeJsonParams<LaunchAppParams>(json)

    override fun createCommand(params: LaunchAppParams): LaunchAppCommand {
        return LaunchAppCommand(
            appId = params.appId,
            clearState = null,
            clearKeychain = null,
            stopApp = null,
            permissions = null,
            launchArguments = null,
            label = null,
            optional = false
        )
    }

    override fun getDeviceId(params: LaunchAppParams): String = params.device_id
} 