package maestro.orchestra

import maestro.js.JsEngine
import maestro.orchestra.util.Env.evaluateScripts

data class AssertEqual(
    val value1: String,
    val value2: String,
) {
    fun evaluateScripts(jsEngine: JsEngine): AssertEqual {
        return copy(
            value1 = value1.evaluateScripts(jsEngine),
            value2 = value2.evaluateScripts(jsEngine),
        )
    }

    fun failureMessage(): String {
        return "Assertion failed: expected '${value2}', but got '${value1}'"
    }
}
