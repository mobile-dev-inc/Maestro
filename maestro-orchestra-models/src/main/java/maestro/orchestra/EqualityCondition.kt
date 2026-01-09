package maestro.orchestra

import maestro.js.JsEngine
import maestro.orchestra.util.Env.evaluateScripts

data class EqualityCondition(
    val value1: String?,
    val value2: String?,
) {
    fun evaluateScripts(jsEngine: JsEngine): EqualityCondition {
        return copy(
            value1 = value1?.evaluateScripts(jsEngine),
            value2 = value2?.evaluateScripts(jsEngine),
        )
    }
}
