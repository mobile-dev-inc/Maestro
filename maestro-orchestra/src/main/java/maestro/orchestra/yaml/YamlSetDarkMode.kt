package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import maestro.orchestra.DarkModeValue

@JsonDeserialize(using = YamlSetDarkModeDeserializer::class)
data class YamlSetDarkMode(
    val value: DarkModeValue,
    val label: String? = null,
    val optional: Boolean = false,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(value: DarkModeValue): YamlSetDarkMode {
            return YamlSetDarkMode(value)
        }
    }
}

class YamlSetDarkModeDeserializer : JsonDeserializer<YamlSetDarkMode>() {

    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): YamlSetDarkMode {
        val mapper = (parser.codec as ObjectMapper)
        val root: TreeNode = mapper.readTree(parser)
        val input = root.fieldNames().asSequence().toList()
        val label = getLabel(root)
        when {
            input.contains("value") -> {
                val parsedValue = root.get("value").toString().replace("\"", "")
                val returnValue = when (parsedValue) {
                    "enabled" -> DarkModeValue.Enable
                    "disabled" -> DarkModeValue.Disable
                    else -> throwInvalidInputException(input)
                }
                return YamlSetDarkMode(returnValue, label)
            }
            (root.isValueNode && root.toString().contains("enabled")) -> {
                return YamlSetDarkMode(DarkModeValue.Enable, label)
            }
            (root.isValueNode && root.toString().contains("disabled")) -> {
                return YamlSetDarkMode(DarkModeValue.Disable, label)
            }
            else -> throwInvalidInputException(input)
        }
    }

    private fun throwInvalidInputException(input: List<String>): Nothing {
        throw IllegalArgumentException(
            "setDarkMode command takes either: \n" +
                    "\t1. enabled: To enable dark mode\n" +
                    "\t2. disabled: To disable dark mode\n" +
                    "\t3. value: To set dark mode to a specific value (enabled or disabled) \n" +
                    "It seems you provided invalid input with: $input"
        )
    }

    private fun getLabel(root: TreeNode): String? {
        return if (root.path("label").isMissingNode) {
            null
        } else {
            root.path("label").toString().replace("\"", "")
        }
    }

}
