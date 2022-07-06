package conductor.orchestra.yaml

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.node.TextNode

@JsonDeserialize(using = YamlElementSelectorDeserializer::class)
interface YamlElementSelectorUnion

data class StringElementSelector(val value: String) : YamlElementSelectorUnion

class YamlElementSelectorDeserializer : JsonDeserializer<YamlElementSelectorUnion>() {

    override fun deserialize(parser: JsonParser, ctx: DeserializationContext): YamlElementSelectorUnion {
        val mapper = parser.codec as ObjectMapper
        val root: TreeNode = mapper.readTree(parser)

        return if (root is TextNode) {
            StringElementSelector(root.textValue())
        } else {
            mapper.convertValue(root, YamlElementSelector::class.java)
        }
    }

}
