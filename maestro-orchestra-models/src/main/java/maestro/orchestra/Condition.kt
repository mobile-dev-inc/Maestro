package maestro.orchestra

import maestro.Platform
import maestro.js.JsEngine
import maestro.orchestra.util.Env.evaluateScripts

data class Condition(
    val platform: Platform? = null,
    val visible: ElementSelector? = null,
    val notVisible: ElementSelector? = null,
    val scriptCondition: String? = null,
    val label: String? = null,
    val equal: AssertEqual? = null,
    val notEqual: AssertNotEqual? = null,
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
            descriptions.add("${it.value1} is equal to ${it.value2}")
        }

        notEqual?.let {
            descriptions.add("${it.value1} is not equal to ${it.value2}")
        }

        return if (descriptions.isEmpty()) {
            "true"
        } else {
            descriptions.joinToString(" and ")
        }
    }

    fun failureMessage(): String {
        label?.let { return it }

        equal?.let { return it.failureMessage() }
        notEqual?.let { return it.failureMessage() }

        return "Assertion is false: ${description()}"
    }

}
