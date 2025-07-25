package maestro.cli.mcp

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.serializer
import maestro.cli.mcp.schema.McpToolInput
import maestro.cli.mcp.schema.McpToolResult
import maestro.cli.mcp.schema.SchemaValidator
import maestro.cli.mcp.schema.ValidationResult

/**
 * MaestroTool provides a type-safe factory for creating MCP tools with automatic schema generation.
 * 
 * This utility combines the Kotlin MCP SDK with automatic schema generation to eliminate manual
 * JSON schema writing while maintaining full type safety and MCP compliance.
 * 
 * ## How it works:
 * 1. Define @Serializable input/output data classes
 * 2. MaestroTool automatically generates JSON schemas from these classes
 * 3. Runtime validation ensures inputs match the schema
 * 4. Responses are automatically structured for MCP compliance
 * 
 * ## Example:
 * ```kotlin
 * // Define input/output types
 * @Serializable
 * data class LaunchAppInput(
 *     val deviceId: String,
 *     val appId: String,
 *     val clearState: Boolean? = null
 * ) : McpToolInput
 * 
 * @Serializable
 * data class LaunchAppOutput(
 *     val success: Boolean,
 *     val deviceId: String,
 *     val message: String
 * )
 * 
 * // Create the tool
 * val tool = MaestroTool.create<LaunchAppInput, LaunchAppOutput>(
 *     name = "launch_app",
 *     description = "Launch an application on a device"
 * ) { input ->
 *     // Tool implementation
 *     LaunchAppOutput(
 *         success = true,
 *         deviceId = input.deviceId,
 *         message = "App ${input.appId} launched"
 *     )
 * }
 * ```
 * 
 * The schemas are automatically generated from the Kotlin types, providing:
 * - Type-safe input validation
 * - Proper MCP-compliant schemas
 * - Structured response formatting
 * - Comprehensive error handling
 */
object MaestroTool {
    inline fun <reified TInput : McpToolInput, reified TOutput : Any> create(
        name: String,
        description: String,
        crossinline handler: suspend (TInput) -> TOutput
    ): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = name,
                description = description,
                inputSchema = SchemaValidator.createInputSchema<TInput>(),
                outputSchema = SchemaValidator.createOutputSchema<TOutput>(),
                annotations = null
            )
        ) { request ->
            // Inline the executeStructuredTool logic
            when (val validationResult = SchemaValidator.validateInput(request, serializer<TInput>())) {
                is ValidationResult.Success -> {
                    try {
                        val result = handler(validationResult.value)
                        SchemaValidator.createStructuredResult(result)
                    } catch (e: Exception) {
                        SchemaValidator.createErrorResult(
                            McpToolResult.Error("Tool execution failed: ${e.message}")
                        )
                    }
                }
                is ValidationResult.Error -> {
                    SchemaValidator.createErrorResult(
                        McpToolResult.Error(validationResult.message)
                    )
                }
            }
        }
    }
}