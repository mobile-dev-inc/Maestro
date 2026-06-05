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
            ArgumentType.NUMBER -> requireNumeric(value.toString()) {
                "Default for argument '$name' on command '$ownerCommand' is not a valid number: $value"
            }
            ArgumentType.BOOLEAN -> requireBoolean(value.toString()) {
                "Default for argument '$name' on command '$ownerCommand' is not a valid boolean: $value"
            }
        }
    }
}

/**
 * Returns [raw] unchanged if it parses as a number; otherwise throws a [SyntaxError]
 * built from [errorMessage]. Shared between argument-default validation and
 * call-site argument coercion.
 */
internal fun requireNumeric(raw: String, errorMessage: () -> String): String {
    raw.toDoubleOrNull() ?: throw SyntaxError(errorMessage())
    return raw
}

/**
 * Returns the lowercase form of [raw] if it is "true" or "false"; otherwise
 * throws a [SyntaxError] built from [errorMessage].
 */
internal fun requireBoolean(raw: String, errorMessage: () -> String): String {
    val normalized = raw.lowercase()
    if (normalized != "true" && normalized != "false") throw SyntaxError(errorMessage())
    return normalized
}
