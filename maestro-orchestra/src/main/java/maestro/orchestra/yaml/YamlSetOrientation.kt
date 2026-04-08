package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.TextNode
import maestro.device.DeviceOrientation

@JsonDeserialize(using = YamlSetOrientationDeserializer::class)
data class YamlSetOrientation(
    val orientation: String,
    val label: String? = null,
    val optional: Boolean = false,
) {
    companion object {
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(orientation: String) = YamlSetOrientation(
            orientation = orientation,
        )
    }
}

class YamlSetOrientationDeserializer : JsonDeserializer<YamlSetOrientation>() {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): YamlSetOrientation? {
        val mapper = (parser.codec as ObjectMapper)
        val root: TreeNode = mapper.readTree(parser)

        if (root.isValueNode) {
            val orientation = (root as TextNode).textValue()
            validateOrientationIfLiteral(orientation)
            return YamlSetOrientation(orientation)
        }

        val orientationNode = root.get("orientation")
            ?: throw IllegalArgumentException("Missing required field 'orientation' in SetOrientation action")

        val orientation = (orientationNode as TextNode).textValue()
        validateOrientationIfLiteral(orientation)
        val label = (root.get("label") as? TextNode)?.textValue()
        val optional = root.get("optional")?.toString()?.toBoolean() ?: false

        return YamlSetOrientation(
            orientation = orientation,
            label = label,
            optional = optional,
        )
    }

    private fun validateOrientationIfLiteral(orientation: String) {
        if (orientation.contains("\${")) {
            return
        }

        val validOrientations = DeviceOrientation.entries
        val isValid = DeviceOrientation.getByName(orientation) != null
        if (!isValid) {
            throw IllegalArgumentException(
                "Unknown orientation: $orientation. Valid orientations are: $validOrientations \n" +
                        "(case insensitive, underscores optional, e.g 'landscape_left', 'landscapeLeft', and 'LANDSCAPE_LEFT' are all valid)"
            )
        }
    }
}