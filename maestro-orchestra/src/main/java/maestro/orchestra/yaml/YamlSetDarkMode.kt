package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.TextNode
import maestro.orchestra.DarkModeValue
import maestro.orchestra.yaml.schema.YamlValues

@JsonDeserialize(using = YamlSetDarkModeDeserializer::class)
data class YamlSetDarkMode(
    @YamlValues(DarkModeValue::class, spelledBy = "yamlValue")
    val value: DarkModeValue,
    val label: String? = null,
    val optional: Boolean = false,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(@YamlValues(DarkModeValue::class, spelledBy = "yamlValue") value: DarkModeValue): YamlSetDarkMode {
            return YamlSetDarkMode(value)
        }
    }
}

/**
 * Accepts `setDarkMode: enabled` and the object form carrying `value`, `label` and `optional`.
 *
 * The accepted vocabulary is not spelled out here: it lives on [DarkModeValue] as `@JsonProperty`
 * wire names, so the parser and the schema derived from these types cannot disagree.
 */
class YamlSetDarkModeDeserializer : JsonDeserializer<YamlSetDarkMode>() {

    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): YamlSetDarkMode {
        val mapper = parser.codec as ObjectMapper
        val root: TreeNode = mapper.readTree(parser)

        if (root.isValueNode) {
            return YamlSetDarkMode(toDarkModeValue(root))
        }

        val valueNode = root.get("value")
            ?: throwInvalidInputException(root.fieldNames().asSequence().toList())

        return YamlSetDarkMode(
            value = toDarkModeValue(valueNode),
            label = root.get("label")?.let { mapper.convertValue(it, String::class.java) },
            optional = root.get("optional")?.let { mapper.convertValue(it, Boolean::class.java) } ?: false,
        )
    }

    /**
     * Looks the word up on [DarkModeValue] rather than letting Jackson convert it, so the constant names stay
     * the MaestroCommand wire format while YAML keeps its own spelling. Still derived from the enum, so
     * the parser and the schema cannot disagree.
     */
    private fun toDarkModeValue(node: TreeNode): DarkModeValue {
        val text = (node as? TextNode)?.textValue() ?: throwInvalidInputException(listOf(node.toString()))
        return DarkModeValue.entries.firstOrNull { it.yamlValue == text } ?: throwInvalidInputException(listOf(text))
    }

    private fun throwInvalidInputException(input: List<String>): Nothing {
        throw IllegalArgumentException(
            "setDarkMode command takes one of the following formats:\n" +
                    "\t- setDarkMode: enabled\n" +
                    "\t- setDarkMode:\n" +
                    "\t    value: enabled\n" +
                    "\t    label: \"Optional label\"\n" +
                    "It seems you provided invalid input with: $input"
        )
    }
}
