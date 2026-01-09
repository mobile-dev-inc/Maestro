package maestro.orchestra

import maestro.Platform
import maestro.js.JsEngine
import maestro.orchestra.util.Env.evaluateScripts

data class Condition(
    val platform: Platform? = null,
    val visible: ElementSelector? = null,
    val notVisible: ElementSelector? = null,
    val scriptCondition: String? = null,
    val equal: EqualityCondition? = null,
    val notEqual: EqualityCondition? = null,
    val label: String? = null,
) {

    fun evaluateScripts(jsEngine: JsEngine): Condition {
        return copy(
            visible = visible?.evaluateScripts(jsEngine),
            notVisible = notVisible?.evaluateScripts(jsEngine),
            scriptCondition = scriptCondition?.evaluateScripts(jsEngine),
            equal = equal?.evaluateScripts(jsEngine),
            notEqual = notEqual?.evaluateScripts(jsEngine),
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

        equal?.let {
            descriptions.add("'${it.value2}' equals '${it.value1}'")
        }

        notEqual?.let {
            descriptions.add("'${it.value2}' does not equal '${it.value1}'")
        }

        return if (descriptions.isEmpty()) {
            "true"
        } else {
            descriptions.joinToString(" and ")
        }
    }

    fun failureMessage(): String {
        label?.let { return it }

        equal?.let { return "Assertion failed: expected '${it.value2}' to equal '${it.value1}'" }
        notEqual?.let { return "Assertion failed: expected '${it.value2}' to not equal '${it.value1}'" }

        return "Assertion is false: ${description()}"
    }
}
