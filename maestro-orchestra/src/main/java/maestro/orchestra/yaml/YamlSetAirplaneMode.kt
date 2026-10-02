package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.TextNode
import maestro.orchestra.AirplaneValue
import maestro.orchestra.yaml.schema.YamlValues

@JsonDeserialize(using = YamlSetAirplaneModeDeserializer::class)
data class YamlSetAirplaneMode(
    @YamlValues(AirplaneValue::class, spelledBy = "yamlValue")
    val value: AirplaneValue,
    val label: String? = null,
    val optional: Boolean = false,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(@YamlValues(AirplaneValue::class, spelledBy = "yamlValue") value: AirplaneValue): YamlSetAirplaneMode {
            return YamlSetAirplaneMode(value)
        }
    }
}

/**
 * Accepts `setAirplaneMode: enabled` and the object form carrying `value`, `label` and `optional`.
 *
 * The accepted vocabulary is not spelled out here: it lives on [AirplaneValue] as `yamlValue`, read both by
 * this deserializer and by the schema derived from these types, so the two cannot disagree. It is
 * deliberately not a `@JsonProperty` on each constant -- that name is the MaestroCommand wire format.
 */
class YamlSetAirplaneModeDeserializer : JsonDeserializer<YamlSetAirplaneMode>() {

    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): YamlSetAirplaneMode {
        val mapper = parser.codec as ObjectMapper
        val root: TreeNode = mapper.readTree(parser)

        if (root.isValueNode) {
            return YamlSetAirplaneMode(toAirplaneValue(root))
        }

        val valueNode = root.get("value")
            ?: throwInvalidInputException(root.fieldNames().asSequence().toList())

        return YamlSetAirplaneMode(
            value = toAirplaneValue(valueNode),
            label = root.get("label")?.let { mapper.convertValue(it, String::class.java) },
            optional = root.get("optional")?.let { mapper.convertValue(it, Boolean::class.java) } ?: false,
        )
    }

    /**
     * Looks the word up on [AirplaneValue] rather than letting Jackson convert it, so the constant names stay
     * the MaestroCommand wire format while YAML keeps its own spelling. Still derived from the enum, so
     * the parser and the schema cannot disagree.
     */
    private fun toAirplaneValue(node: TreeNode): AirplaneValue {
        val text = (node as? TextNode)?.textValue() ?: throwInvalidInputException(listOf(node.toString()))
        return AirplaneValue.entries.firstOrNull { it.yamlValue == text } ?: throwInvalidInputException(listOf(text))
    }

    private fun throwInvalidInputException(input: List<String>): Nothing {
        throw IllegalArgumentException(
            "setAirplaneMode command takes either: \n" +
                    "\t1. enabled: To enable airplane mode\n" +
                    "\t2. disabled: To disable airplane mode\n" +
                    "\t3. value: To set airplane mode to a specific value (enabled or disabled) \n" +
                    "It seems you provided invalid input with: $input"
        )
    }
}
