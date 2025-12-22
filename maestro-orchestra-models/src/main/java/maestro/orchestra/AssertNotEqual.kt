data class AssertNotEqual(
    val value1: String,
    val value2: String,
) {
    fun evaluateScripts(jsEngine: JsEngine): AssertNotEqual {
        return copy(
            value1 = value1.evaluateScripts(jsEngine),
            value2 = value2.evaluateScripts(jsEngine),
        )
    }
}