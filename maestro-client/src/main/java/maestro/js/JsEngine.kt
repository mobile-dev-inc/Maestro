package maestro.js

import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import org.mozilla.javascript.Context
import org.mozilla.javascript.ScriptableObject
import java.util.concurrent.TimeUnit

class JsEngine(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()
) {

    private lateinit var context: Context
    private lateinit var currentScope: JsScope

    fun init() {
        context = Context.enter()
        currentScope = JsScope()
        context.initSafeStandardObjects(currentScope)

        val jsHttp = JsHttp(httpClient)
        jsHttp.defineFunctionProperties(
            arrayOf("request", "get", "post", "put", "delete"),
            JsHttp::class.java,
            ScriptableObject.DONTENUM
        )
        context.initSafeStandardObjects(jsHttp)
        currentScope.put("http", currentScope, jsHttp)

        context.evaluateString(
            currentScope,
            Js.initScript,
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
    ): Any? {
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
        )
    }

}
