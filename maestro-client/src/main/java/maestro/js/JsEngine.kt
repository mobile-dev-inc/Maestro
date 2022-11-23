package maestro.js

import org.mozilla.javascript.Context

class JsEngine {

    private lateinit var context: Context
    private lateinit var currentScope: JsScope

    fun init() {
        context = Context.enter()
        currentScope = JsScope()
        context.initSafeStandardObjects(currentScope)

        context.evaluateString(
            currentScope,
            """
                function json(text) {
                    return JSON.parse(text)
                }
            """.trimIndent(),
            "maestro-runtime",
            1,
            null
        )
    }

    fun enterScope() {
        val subScope = JsScope()
        subScope.parentScope = currentScope
        currentScope = subScope
    }

    fun leaveScope() {
        currentScope = currentScope.parentScope as JsScope
    }

    fun evaluateScript(script: String): String {
        return context.evaluateString(
            currentScope,
            script,
            "inline-script",
            1,
            null
        ).toString()
    }

}