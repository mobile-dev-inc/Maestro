package maestro.cli.mcp.schema

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Base interface for all MCP tool input parameters
 */
interface McpToolInput

/**
 * Sealed class representing standardized MCP tool results
 */
@Serializable
sealed class McpToolResult {
    
    /**
     * Successful tool execution result
     */
    @Serializable
    data class Success(
        val deviceId: String? = null,
        val message: String? = null,
        val data: JsonElement? = null
    ) : McpToolResult()
    
    /**
     * Tool execution error result
     */
    @Serializable
    data class Error(
        val message: String,
        val code: String? = null,
        val details: JsonElement? = null
    ) : McpToolResult()
}