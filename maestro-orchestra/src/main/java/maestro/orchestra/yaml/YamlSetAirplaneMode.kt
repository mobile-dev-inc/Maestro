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

class YamlSetAirplaneModeDeserializer : JsonDeserializer<YamlSetAirplaneMode>() {

    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): YamlSetAirplaneMode {
        val mapper = (parser.codec as ObjectMapper)
        val root: TreeNode = mapper.readTree(parser)
        val input = root.fieldNames().asSequence().toList()
        val label = getLabel(root)
        when {
            input.contains("value") -> {
                val parsedValue = root.get("value").toString().replace("\"", "")
                val returnValue = when (parsedValue) {
                    "enabled" -> AirplaneValue.Enable
                    "disabled" -> AirplaneValue.Disable
                    else -> throwInvalidInputException(input)
                }
                return YamlSetAirplaneMode(returnValue, label)
            }
            (root.isValueNode && root.toString().contains("enabled")) -> {
                return YamlSetAirplaneMode(AirplaneValue.Enable, label)
            }
            (root.isValueNode && root.toString().contains("disabled")) -> {
                return YamlSetAirplaneMode(AirplaneValue.Disable, label)
            }
            else -> throwInvalidInputException(input)
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

    private fun getLabel(root: TreeNode): String? {
        return if (root.path("label").isMissingNode) {
            null
        } else {
            root.path("label").toString().replace("\"", "")
        }
    }

}
