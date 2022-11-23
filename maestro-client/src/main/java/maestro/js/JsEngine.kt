package maestro.js

import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
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
                
                const output = {}
            """.trimIndent(),
            "maestro-runtime",
            1,
            null
        )
        currentScope.sealObject()

        // We are entering a sub-scope so that no more declarations can be made
        // on the root scope that is now sealed.
        enterScope()
    }

    fun enterScope() {
        val subScope = JsScope()
        subScope.parentScope = currentScope
        currentScope = subScope
    }

    fun leaveScope() {
        currentScope = currentScope.parentScope as JsScope
    }

    fun evaluateScript(
        script: String,
        env: Map<String, String> = emptyMap(),
        sourceName: String = "inline-script",
        runInSubSope: Boolean = false
    ): String {
        val scope = if (runInSubSope) {
            // We create a new scope for each evaluation to prevent local variables
            // from clashing with each other across multiple scripts.
            // Only 'output' is shared across scopes.
            JsScope()
                .apply { parentScope = currentScope }
        } else {
            currentScope
        }

        if (env.isNotEmpty()) {
            env.forEach { (key, value) ->
                context.evaluateString(
                    scope,
                    "var $key = '${Jsoup.clean(value, Safelist.none())}'",
                    sourceName,
                    1,
                    null
                ).toString()
            }
        }

        return context.evaluateString(
            scope,
            script,
            sourceName,
            1,
            null
        ).toString()
    }

}