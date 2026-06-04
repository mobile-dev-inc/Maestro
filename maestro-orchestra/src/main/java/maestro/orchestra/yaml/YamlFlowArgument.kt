package maestro.orchestra.yaml

import maestro.orchestra.ArgumentType
import maestro.orchestra.CustomCommandArgument
import maestro.orchestra.error.SyntaxError

data class YamlFlowArgument(
    val name: String,
    val type: String = "string",
    val required: Boolean = false,
    val default: Any? = null,
) {

    fun toCustomCommandArgument(ownerCommand: String): CustomCommandArgument {
        val parsedType = parseType(type, ownerCommand)
        val coercedDefault = default?.let { coerceDefault(it, parsedType, ownerCommand) }
        return CustomCommandArgument(
            name = name,
            type = parsedType,
            required = required,
            default = coercedDefault,
        )
    }

    private fun parseType(raw: String, ownerCommand: String): ArgumentType {
        return when (raw.lowercase()) {
            "string" -> ArgumentType.STRING
            "number" -> ArgumentType.NUMBER
            "boolean", "bool" -> ArgumentType.BOOLEAN
            else -> throw SyntaxError(
                "Invalid argument type '$raw' for command '$ownerCommand'. " +
                    "Allowed: string, number, boolean."
            )
        }
    }

    private fun coerceDefault(value: Any, type: ArgumentType, ownerCommand: String): String {
        return when (type) {
            ArgumentType.STRING -> value.toString()
            ArgumentType.NUMBER -> {
                val asString = value.toString()
                asString.toDoubleOrNull() ?: throw SyntaxError(
                    "Default for argument '$name' on command '$ownerCommand' is not a valid number: $asString"
                )
                asString
            }
            ArgumentType.BOOLEAN -> when (value.toString().lowercase()) {
                "true", "false" -> value.toString().lowercase()
                else -> throw SyntaxError(
                    "Default for argument '$name' on command '$ownerCommand' is not a valid boolean: $value"
                )
            }
        }
    }
}
