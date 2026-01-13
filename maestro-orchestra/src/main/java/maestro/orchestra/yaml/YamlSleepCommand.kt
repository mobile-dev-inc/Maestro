package maestro.orchestra.yaml
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

@JsonDeserialize(using = YamlSleepCommandDeserializer::class)
data class YamlSleepCommand(
    val time: String?,
    val label: String? = null,
)

class YamlSleepCommandDeserializer : JsonDeserializer<YamlSleepCommand>() {

    override fun deserialize(
        parser: JsonParser,
        ctx: DeserializationContext
    ): YamlSleepCommand {

        return when (parser.currentToken) {

            JsonToken.VALUE_STRING,
            JsonToken.VALUE_NUMBER_INT,
            JsonToken.VALUE_NUMBER_FLOAT ->
                YamlSleepCommand(
                    time = parser.text,
                    label = null
                )

            JsonToken.START_OBJECT -> {
                // Let Jackson deserialize the object normally
                val node = parser.codec.readTree<JsonNode>(parser)

                val timeNode = node.get("time")
                    ?: throw JsonMappingException.from(parser,"Sleep command must contain property 'time'")

                val time = when {
                    timeNode.isTextual || timeNode.isNumber -> timeNode.asText()
                    else -> throw JsonMappingException.from(parser,"Sleep command property 'time' must be a string or number")
                }

                val label = node.get("label")?.asText()

                YamlSleepCommand(time, label)
            }

            else -> ctx.handleUnexpectedToken(
                YamlSleepCommand::class.java,
                parser
            ) as YamlSleepCommand
        }
    }
}