package maestro.orchestra

import maestro.js.JsEngine
import maestro.orchestra.util.Env.evaluateScripts

data class Condition(
    val visible: ElementSelector? = null,
    val notVisible: ElementSelector? = null,
    val scriptCondition: String? = null
) {

    fun evaluateScripts(jsEngine: JsEngine): Condition {
        return copy(
            visible = visible?.evaluateScripts(jsEngine),
            notVisible = notVisible?.evaluateScripts(jsEngine),
            scriptCondition = scriptCondition?.evaluateScripts(jsEngine),
        )
    }

    fun description(): String {
        val descriptions = mutableListOf<String>()

        visible?.let {
            descriptions.add("${it.description()} is visible")
        }

        notVisible?.let {
            descriptions.add("${it.description()} is not visible")
        }

        scriptCondition?.let {
            descriptions.add("$it is true")
        }

        return if (descriptions.isEmpty()) {
            "true"
        } else {
            descriptions.joinToString(" and ")
        }
    }

}
