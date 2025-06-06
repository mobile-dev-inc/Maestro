package maestro.cli.mcp

/**
 * Annotation for individual fields to specify JSON Schema properties
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
annotation class JsonSchemaField(
    val description: String,
    val required: Boolean = true,
    val pattern: String = "",
    val example: String = ""
) 