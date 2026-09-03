package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import maestro.orchestra.AirplaneValue

@JsonDeserialize(using = YamlSetAirplaneModeDeserializer::class)
data class YamlSetAirplaneMode(
    val value: AirplaneValue,
    val label: String? = null,
    val optional: Boolean = false,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(value: AirplaneValue): YamlSetAirplaneMode {
            return YamlSetAirplaneMode(value)
        }
    }
}

/**
 * Accepts `setAirplaneMode: enabled` and the object form carrying `value`, `label` and `optional`.
 *
 * The accepted vocabulary is not spelled out here: it lives on [AirplaneValue] as `@JsonProperty`
 * wire names, so the parser and the schema derived from these types cannot disagree.
 */
class YamlSetAirplaneModeDeserializer : JsonDeserializer<YamlSetAirplaneMode>() {

    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): YamlSetAirplaneMode {
        val mapper = parser.codec as ObjectMapper
        val root: TreeNode = mapper.readTree(parser)

        if (root.isValueNode) {
            return YamlSetAirplaneMode(toAirplaneValue(mapper, root))
        }

        val valueNode = root.get("value")
            ?: throwInvalidInputException(root.fieldNames().asSequence().toList())

        return YamlSetAirplaneMode(
            value = toAirplaneValue(mapper, valueNode),
            label = root.get("label")?.let { mapper.convertValue(it, String::class.java) },
            optional = root.get("optional")?.let { mapper.convertValue(it, Boolean::class.java) } ?: false,
        )
    }

    private fun toAirplaneValue(mapper: ObjectMapper, node: TreeNode): AirplaneValue {
        return try {
            mapper.convertValue(node, AirplaneValue::class.java)
        } catch (e: IllegalArgumentException) {
            throwInvalidInputException(listOf(node.toString()))
        }
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
