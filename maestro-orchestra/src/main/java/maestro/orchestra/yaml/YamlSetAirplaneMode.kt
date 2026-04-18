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

@JsonDeserialize(using = YamlSetAirplaneModeDeserializer::class)
data class YamlSetAirplaneMode(
    val value: String,
    val label: String? = null,
    val optional: Boolean = false,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(value: String): YamlSetAirplaneMode {
            return YamlSetAirplaneMode(value)
        }
    }
}

class YamlSetAirplaneModeDeserializer : JsonDeserializer<YamlSetAirplaneMode>() {

    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): YamlSetAirplaneMode {
        val mapper = (parser.codec as ObjectMapper)
        val root: TreeNode = mapper.readTree(parser)

        if (root.isValueNode) {
            val value = (root as TextNode).textValue()
            validateIfLiteral(value)
            return YamlSetAirplaneMode(value)
        }

        val valueNode = root.get("value")
            ?: throw IllegalArgumentException("Missing required field 'value' in setAirplaneMode action")

        val value = (valueNode as TextNode).textValue()
        validateIfLiteral(value)
        val label = (root.get("label") as? TextNode)?.textValue()
        val optional = root.get("optional")?.toString()?.toBoolean() ?: false

        return YamlSetAirplaneMode(
            value = value,
            label = label,
            optional = optional,
        )
    }

    private fun validateIfLiteral(value: String) {
        if (value.contains("\${")) {
            return
        }

        AirplaneValue.fromString(value)
            ?: throw IllegalArgumentException(
                "setAirplaneMode command takes either: \n" +
                        "\t1. enabled: To enable airplane mode\n" +
                        "\t2. disabled: To disable airplane mode\n" +
                        "It seems you provided invalid input: $value"
            )
    }
}
