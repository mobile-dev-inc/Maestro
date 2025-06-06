package maestro.cli.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import maestro.cli.mcp.JsonSchemaGenerator

/**
 * Base abstract class for all MCP tools.
 * Provides common functionality including schema generation, parameter deserialization,
 * response formatting, and error handling.
 */
abstract class McpTool {
    /**
     * Tool definition for MCP registration
     */
    abstract val definition: ToolDefinition
    
    /**
     * Execute the tool with the provided arguments
     */
    abstract suspend fun execute(arguments: JsonElement): ToolCallResult

    /**
     * Safely execute a block of code with automatic error handling
     */
    protected fun safeExecute(block: () -> ToolCallResult): ToolCallResult {
        return try {
            block()
        } catch (e: Exception) {
            errorResponse("${this.javaClass.simpleName} failed: ${e.message}")
        }
    }

    /**
     * Create a successful response with text content
     */
    protected fun successResponse(text: String): ToolCallResult {
        return ToolCallResult(
            content = listOf(
                TextContent(text = text)
            )
        )
    }

    /**
     * Create a successful response with multiple content items
     */
    protected fun successResponse(content: List<ContentItem>): ToolCallResult {
        return ToolCallResult(content = content)
    }

    /**
     * Create a successful response with automatic JSON serialization
     */
    protected inline fun <reified T> successResponse(result: T): ToolCallResult {
        val text = when (result) {
            is String -> result
            else -> Json.encodeToString(result)
        }
        return ToolCallResult(
            content = listOf(
                TextContent(
                    text = text
                )
            )
        )
    }

    /**
     * Create an error response
     */
    protected fun errorResponse(message: String): ToolCallResult {
        return ToolCallResult(
            content = listOf(
                TextContent(text = message)
            ),
            isError = true
        )
    }

    /**
     * Generate tool definition for a parameter class using automatic JSON Schema generation.
     * Use this in your tool's definition property:
     * override val definition = generateToolDefinition<ParamsType>("tool_name", "Tool description")
     */
    protected inline fun <reified P : Any> generateToolDefinition(name: String, description: String): ToolDefinition {
        return JsonSchemaGenerator.generateToolDefinition<P>(name, description)
    }

    /**
     * Deserialize JSON parameters to typed parameters using automatic deserialization.
     * Use this in parameter parsing:
     * val params = deserializeJsonParams<ParamsType>(json)
     */
    protected inline fun <reified P : Any> deserializeJsonParams(json: JsonElement): P {
        return Json.decodeFromJsonElement(serializer<P>(), json)
    }
} 