package maestro.cli.mcp

import maestro.cli.mcp.ToolDefinition
import maestro.cli.mcp.ToolInputSchema
import kotlin.reflect.KClass
import kotlin.reflect.KProperty1
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.javaType


object JsonSchemaGenerator {
    
    /**
     * Generate ToolDefinition from a parameter class with explicit tool name and description
     */
    inline fun <reified T : Any> generateToolDefinition(name: String, description: String): ToolDefinition {
        return generateToolDefinition(T::class, name, description)
    }
    
    fun <T : Any> generateToolDefinition(clazz: KClass<T>, name: String, description: String): ToolDefinition {
        val properties = mutableMapOf<String, ToolInputSchema>()
        val required = mutableListOf<String>()
        
        // Process each property in the data class
        clazz.memberProperties.forEach { property ->
            val fieldAnnotation = property.findAnnotation<JsonSchemaField>()
            if (fieldAnnotation != null) {
                val fieldName = property.name
                val fieldType = getJsonSchemaType(property)
                
                properties[fieldName] = ToolInputSchema(
                    type = fieldType,
                    description = fieldAnnotation.description
                )
                
                if (fieldAnnotation.required) {
                    required.add(fieldName)
                }
            }
        }
        
        return ToolDefinition(
            name = name,
            description = description,
            inputSchema = ToolInputSchema(
                type = "object",
                properties = properties,
                required = required
            )
        )
    }
    
    /**
     * Map Kotlin types to JSON Schema types
     */
    private fun getJsonSchemaType(property: KProperty1<*, *>): String {
        val javaType = property.returnType.javaType
        
        return when {
            javaType == String::class.java -> "string"
            javaType == Boolean::class.java || javaType == Boolean::class.javaObjectType -> "boolean"
            javaType == Int::class.java || javaType == Int::class.javaObjectType -> "integer"
            javaType == Long::class.java || javaType == Long::class.javaObjectType -> "integer"
            javaType == Double::class.java || javaType == Double::class.javaObjectType -> "number"
            javaType == Float::class.java || javaType == Float::class.javaObjectType -> "number"
            javaType.toString().contains("Map") -> "object"
            javaType.toString().contains("List") || javaType.toString().contains("Array") -> "array"
            else -> "string" // Default fallback
        }
    }
} 