package maestro.cli.mcp.schema

import com.xemantic.ai.tool.schema.generator.jsonSchemaOf
import com.xemantic.ai.tool.schema.ObjectSchema
import io.modelcontextprotocol.kotlin.sdk.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer

/**
 * Schema validation utility for MCP tools using automatic JSON schema generation.
 * 
 * This class provides a bridge between Kotlin's type system and MCP's JSON schema requirements
 * by automatically generating schemas from @Serializable data classes and validating tool inputs/outputs.
 * 
 * Key features:
 * - Automatic schema generation from Kotlin @Serializable classes using xemantic-ai-tool-schema
 * - Type-safe input validation with detailed error messages
 * - Structured response generation with both content and structuredContent for MCP compliance
 * - Co-located schema definitions with tool implementations for better maintainability
 * 
 * Usage pattern:
 * 1. Define @Serializable input/output classes implementing McpToolInput
 * 2. Use createInputSchema<T>() and createOutputSchema<T>() for Tool registration
 * 3. Use validateInput() for runtime input validation
 * 4. Use createStructuredResult() for MCP-compliant responses
 */
object SchemaValidator {
    
    /**
     * Json instance for serialization/deserialization.
     */
    val json = Json

    /**
     * Generate a Tool.Input schema from a serializable input class.
     * 
     * Uses jsonSchemaOf() from xemantic-ai-tool-schema to automatically generate
     * JSON schema from Kotlin class structure, including property types and required fields.
     */
    inline fun <reified T : McpToolInput> createInputSchema(): Tool.Input {
        val objectSchema = jsonSchemaOf<T>() as ObjectSchema
        val jsonElement = json.encodeToJsonElement(objectSchema)
        val jsonObject = jsonElement.jsonObject
        
        return Tool.Input(
            properties = jsonObject["properties"]!!.jsonObject,
            required = jsonObject["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        )
    }
    
    /**
     * Generate a Tool.Output schema from a serializable output class.
     * 
     * Similar to createInputSchema but for tool outputs, enabling MCP clients
     * to understand the structure of data returned by tools.
     */
    inline fun <reified T : Any> createOutputSchema(): Tool.Output {
        val objectSchema = jsonSchemaOf<T>() as ObjectSchema
        val jsonElement = json.encodeToJsonElement(objectSchema)
        val jsonObject = jsonElement.jsonObject
        
        return Tool.Output(
            properties = jsonObject["properties"]!!.jsonObject,
            required = jsonObject["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        )
    }

    /**
     * Validate and parse input parameters from MCP request.
     * 
     * Deserializes request arguments into the specified input type with proper error handling.
     * Returns ValidationResult.Success with parsed input or ValidationResult.Error with details.
     */
    inline fun <reified T : McpToolInput> validateInput(
        request: CallToolRequest,
        serializer: KSerializer<T>
    ): ValidationResult<T> {
        return try {
            val jsonElement = json.encodeToJsonElement(JsonObject.serializer(), request.arguments)
            val input = json.decodeFromJsonElement(serializer, jsonElement)
            ValidationResult.Success(input)
        } catch (e: Exception) {
            ValidationResult.Error("Input validation failed: ${e.message}")
        }
    }

    /**
     * Create a structured CallToolResult with both content and structuredContent.
     * 
     * This is the preferred method for creating MCP-compliant responses that include
     * both human-readable content and machine-readable structured data conforming to outputSchema.
     */
    inline fun <reified T : Any> createStructuredResult(
        structuredData: T
    ): CallToolResult {
        // Create JSON representation for content (backwards compatibility)
        val jsonString = json.encodeToString(serializer<T>(), structuredData)
        val content = listOf(TextContent(jsonString))
        
        // Create structured content that conforms to output schema
        val structuredContent = json.encodeToJsonElement(serializer<T>(), structuredData).jsonObject
        
        return CallToolResult(
            content = content,
            structuredContent = structuredContent,
            isError = false
        )
    }

    /**
     * Create an error CallToolResult from a McpToolResult.Error.
     * 
     * Standardizes error responses with consistent structure and proper error flag.
     */
    fun createErrorResult(error: McpToolResult.Error): CallToolResult {
        val errorJson = buildJsonObject {
            put("success", false)
            put("error", error.message)
            error.code?.let { put("error_code", it) }
            error.details?.let { put("details", it) }
        }
        
        return CallToolResult(
            content = listOf(TextContent(errorJson.toString())),
            isError = true
        )
    }
}

/**
 * Sealed class representing validation results
 */
sealed class ValidationResult<out T> {
    data class Success<T>(val value: T) : ValidationResult<T>()
    data class Error(val message: String) : ValidationResult<Nothing>()
}

