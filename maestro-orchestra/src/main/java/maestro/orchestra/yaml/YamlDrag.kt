package maestro.orchestra.yaml

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

private const val DEFAULT_DURATION_IN_MILLIS = 3000L

@JsonDeserialize(using = YamlDragDeserializer::class)
data class YamlDrag(
    val from: YamlElementSelectorUnion? = null,
    val to: YamlElementSelectorUnion? = null,
    val offset: String? = null,
    val duration: Long = DEFAULT_DURATION_IN_MILLIS,
    val waitToSettleTimeoutMs: Int? = null,
    val label: String? = null,
    val optional: Boolean = false,
)

class YamlDragDeserializer : JsonDeserializer<YamlDrag>() {

    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): YamlDrag {
        val mapper = parser.codec as ObjectMapper
        val root: TreeNode = mapper.readTree(parser)

        val fromNode = root.get("from")
        val toNode = root.get("to")
        val offsetNode = root.get("offset")

        check(fromNode != null) {
            "Drag command requires 'from' field. Example:\n" +
                "- drag:\n" +
                "    from: \"50%, 30%\"\n" +
                "    to: \"50%, 60%\""
        }

        check(toNode != null || offsetNode != null) {
            "Drag command requires either 'to' or 'offset' field. Example:\n" +
                "- drag:\n" +
                "    from: \"50%, 30%\"\n" +
                "    to: \"50%, 60%\"\n" +
                "Or:\n" +
                "- drag:\n" +
                "    from: \"50%, 30%\"\n" +
                "    offset: \"0%, 30%\""
        }

        check(!(toNode != null && offsetNode != null)) {
            "Drag command cannot have both 'to' and 'offset' fields. Use one or the other."
        }

        val from = parseElementField(mapper, fromNode)
        val to = toNode?.let { parseElementField(mapper, it) }
        val offset = offsetNode?.let { it.toString().trim('"') }
        val duration = root.path("duration").let {
            if (it.isMissingNode) DEFAULT_DURATION_IN_MILLIS else it.toString().replace("\"", "").toLong()
        }
        val waitToSettleTimeoutMs = root.path("waitToSettleTimeoutMs").let {
            if (it.isMissingNode) null else it.toString().replace("\"", "").toIntOrNull()
        }
        val label = root.path("label").let {
            if (it.isMissingNode) null else it.toString().replace("\"", "")
        }
        val optional = root.path("optional").let {
            if (it.isMissingNode) false else it.toString().replace("\"", "").toBoolean()
        }

        if (from is StringElementSelector) {
            validatePercentageCoordinate(from.value, "from")
        }
        if (to is StringElementSelector) {
            validatePercentageCoordinate(to.value, "to")
        }
        if (offset != null) {
            validatePercentageOffset(offset)
        }

        return YamlDrag(
            from = from,
            to = to,
            offset = offset,
            duration = duration,
            waitToSettleTimeoutMs = waitToSettleTimeoutMs,
            label = label,
            optional = optional,
        )
    }

    private fun parseElementField(mapper: ObjectMapper, node: TreeNode): YamlElementSelectorUnion {
        val nodeStr = node.toString()
        return if (nodeStr.startsWith("\"") && nodeStr.endsWith("\"")) {
            val value = nodeStr.trim('"')
            if (value.contains("%") && value.contains(",")) {
                StringElementSelector(value)
            } else {
                YamlElementSelector(text = value)
            }
        } else {
            mapper.treeToValue(node, YamlElementSelector::class.java)
        }
    }

    private fun validatePercentageCoordinate(coord: String, fieldName: String) {
        if (!coord.contains("%")) {
            throw IllegalArgumentException(
                "Drag command '$fieldName' must use percentage coordinates (e.g., \"50%, 30%\"). " +
                    "Absolute coordinates are not supported for device compatibility."
            )
        }
        val parts = coord.replace("%", "").split(",").map { it.trim() }
        check(parts.size == 2) {
            "Invalid '$fieldName' coordinate format: $coord. Expected format: \"X%, Y%\""
        }
        val (x, y) = parts.map { it.toIntOrNull() }
        check(x != null && y != null) {
            "Invalid '$fieldName' coordinate values: $coord. Values must be integers."
        }
        check(x in 0..100 && y in 0..100) {
            "Invalid '$fieldName' coordinate values: $coord. Values must be between 0 and 100."
        }
    }

    private fun validatePercentageOffset(offset: String) {
        if (!offset.contains("%")) {
            throw IllegalArgumentException(
                "Drag command 'offset' must use percentage values (e.g., \"0%, 30%\"). " +
                    "Absolute values are not supported for device compatibility."
            )
        }
        val parts = offset.replace("%", "").split(",").map { it.trim() }
        check(parts.size == 2) {
            "Invalid 'offset' format: $offset. Expected format: \"X%, Y%\""
        }
        val (x, y) = parts.map { it.toIntOrNull() }
        check(x != null && y != null) {
            "Invalid 'offset' values: $offset. Values must be integers."
        }
    }
}
