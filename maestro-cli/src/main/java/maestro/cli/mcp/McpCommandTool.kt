package maestro.cli.mcp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import maestro.cli.mcp.ToolCallResult
import maestro.cli.mcp.ToolDefinition
import maestro.cli.session.MaestroSessionManager
import kotlinx.coroutines.runBlocking
import maestro.orchestra.Orchestra
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Command

/**
 * Base class for tools that directly construct Maestro Commands.
 * Handles common patterns: session management, Orchestra creation, error handling.
 * 
 * @param P Parameter data class type
 * @param C Command type that extends maestro.orchestra.Command
 */
abstract class McpCommandTool<P : Any, C : Command>(
    protected val sessionManager: MaestroSessionManager
) : McpTool() {

    /**
     * Tool name for MCP registration
     */
    abstract val toolName: String

    /**
     * Tool description for MCP registration
     */
    abstract val toolDescription: String

    /**
     * Tool definition for MCP registration.
     * Implement as: override val definition = generateToolDefinition<ParamsType>(toolName, toolDescription)
     */
    abstract override val definition: ToolDefinition

    /**
     * Deserialize JSON arguments to typed parameters
     */
    abstract fun deserializeParams(json: JsonElement): P

    /**
     * Create the Maestro command from typed parameters
     */
    abstract fun createCommand(params: P): C

    /**
     * Extract device_id from parameters (must be implemented per tool)
     */
    abstract fun getDeviceId(params: P): String

    override suspend fun execute(arguments: JsonElement): ToolCallResult {
        return safeExecute {
            // Deserialize and validate parameters
            val params = try {
                deserializeParams(arguments)
            } catch (e: SerializationException) {
                return@safeExecute errorResponse("Invalid parameters: ${e.message?.substringAfter(":")?.trim()}")
            } catch (e: IllegalArgumentException) {
                return@safeExecute errorResponse("Validation error: ${e.message}")
            }

            // Create the command
            val command = createCommand(params)
            val deviceId = getDeviceId(params)

            // Execute with session management
            sessionManager.newSession(
                deviceId = deviceId,
                host = null,
                port = null,
                driverHostPort = null
            ) { session ->
                try {
                    val orchestra = Orchestra(session.maestro)
                    runBlocking {
                        orchestra.executeCommands(listOf(MaestroCommand(command = command)))
                    }
                    
                    return@newSession successResponse("Successfully executed ${command.javaClass.simpleName}")
                } catch (e: Exception) {
                    return@newSession errorResponse("Failed to execute command: ${e.message}")
                }
            }
        }
    }
}



 