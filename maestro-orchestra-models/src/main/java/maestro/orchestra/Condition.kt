package maestro.orchestra

import maestro.Platform
import maestro.js.JsEngine
import maestro.orchestra.util.Env.evaluateScripts

data class Condition(
    val platform: Platform? = null,
    val visible: ElementSelector? = null,
    val notVisible: ElementSelector? = null,
    val scriptCondition: String? = null,
    val assertEqual: AssertEqual? = null,
    val assertNotEqual: AssertNotEqual? = null,
    val label: String? = null,
) {

    fun evaluateScripts(jsEngine: JsEngine): Condition {
        return copy(
            visible = visible?.evaluateScripts(jsEngine),
            notVisible = notVisible?.evaluateScripts(jsEngine),
            scriptCondition = scriptCondition?.evaluateScripts(jsEngine),
            assertEqual = assertEqual?.evaluateScripts(jsEngine),
            assertNotEqual = assertNotEqual?.ev.evaluateScripts(jsEngine),
        )
    }

    fun description(): String {
        if(label != null){
            return label
        }

        val descriptions = mutableListOf<String>()

        platform?.let {
            descriptions.add("Platform is $it")
        }

        visible?.let {
            descriptions.add("${it.description()} is visible")
        }

        notVisible?.let {
            descriptions.add("${it.description()} is not visible")
        }

        scriptCondition?.let {
            descriptions.add("$it is true")
        }

        assertEqual?.let {
            description.add("${it.value1} is equal to ${it.value2}")
        }

        assertNotEqual?.let {
            description.add("${it.value1} is not equal to ${it.value2}")
        }

        return if (descriptions.isEmpty()) {
            "true"
        } else {
            descriptions.joinToString(" and ")
        }
    }

}
