package ios.hierarchy

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.TreeNode
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.module.kotlin.convertValue

@JsonDeserialize(using = XCUIElementDeserializer::class)
interface XCUIElement

data class IdbElementNode(val children: List<AccessibilityNode>) : XCUIElement

@JsonDeserialize(`as` = XCUIElementNode::class)
data class XCUIElementNode(
    val label: String,
    val elementType: Int,
    val identifier: String,
    val horizontalSizeClass: Int,
    val windowContextID: Long,
    val verticalSizeClass: Int,
    val selected: Boolean,
    val displayID: Int,
    val hasFocus: Boolean,
    val placeholderValue: String?,
    val value: String?,
    val frame: Frame,
    val enabled: Boolean,
    val title: String?,
    val children: ArrayList<XCUIElement>?,
) : XCUIElement

class XCUIElementDeserializer : JsonDeserializer<XCUIElement>() {

    override fun deserialize(parser: JsonParser, ctxt: DeserializationContext): XCUIElement {
        val mapper = parser.codec as ObjectMapper
        val root: TreeNode = mapper.readTree(parser)
        if (root.get(ELEMENT_TYPE_PROPERTY) != null) {
            return mapper.convertValue(root, XCUIElementNode::class.java)
        }
        val accessibilityNode: List<AccessibilityNode> = mapper.convertValue(root)
        return IdbElementNode(accessibilityNode)
    }

    companion object {
        private const val ELEMENT_TYPE_PROPERTY = "elementType"
    }
}