package maestro.js

import maestro.utils.HttpClient
import net.datafaker.Faker
import net.datafaker.providers.base.AbstractProvider
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.proxy.ProxyObject
import java.io.ByteArrayOutputStream
import java.util.logging.Handler
import java.util.logging.LogRecord
import kotlin.time.Duration.Companion.minutes

private val NULL_HANDLER = object : Handler() {
    override fun publish(record: LogRecord?) {}

    override fun flush() {}

    override fun close() {}
}

class GraalJsEngine(
    httpClient: OkHttpClient = HttpClient.build(
        name = "GraalJsEngine",
        readTimeout = 5.minutes,
        writeTimeout = 5.minutes,
        protocols = listOf(Protocol.HTTP_1_1)
    ),
    platform: String = "unknown",
) : JsEngine {

    private val httpBinding = GraalJsHttp(httpClient)
    private val outputBinding = HashMap<String, Any>()
    private val maestroBinding = HashMap<String, Any?>()
    private val envBinding = HashMap<String, String>()
    private val envScopeStack = mutableListOf<HashMap<String, String>>()  // for scope isolation

    // Keys that should never be removed from context bindings
    private val permanentBindingKeys = setOf(
        "http", "faker", "output", "maestro",  // Kotlin-side bindings
        "json", "relativePoint"                 // JS-defined helper functions
    )

    private val faker = Faker()
    private val fakerPublicClasses = mutableSetOf<Class<*>>() // To avoid re-processing the same class multiple times

    private var onLogMessage: (String) -> Unit = {}

    private var platform = platform

    // Single reusable context - created lazily on first evaluation
    private var sharedContext: Context? = null

    override fun close() {
        sharedContext?.close()
        sharedContext = null
    }

    override fun onLogMessage(callback: (String) -> Unit) {
        onLogMessage = callback
    }

    override fun enterScope() {}

    override fun leaveScope() {}

    override fun putEnv(key: String, value: String) {
        this.envBinding[key] = value
    }

    override fun setCopiedText(text: String?) {
        this.maestroBinding["copiedText"] = text
    }

    override fun evaluateScript(
        script: String,
        env: Map<String, String>,
        sourceName: String,
        runInSubScope: Boolean,
    ): Value {
        if (runInSubScope) {
            // Save current environment state
            enterEnvScope()
            try {
                // Add the new env vars on top of the current scope
                envBinding.putAll(env)
                return evalWithIIFE(script, sourceName)
            } finally {
                // Restore previous environment state
                leaveEnvScope()
            }
        } else {
            // Original behavior - directly add to envBinding
            envBinding.putAll(env)
            return evalWithIIFE(script, sourceName)
        }
    }

    /**
     * Evaluates a script wrapped in an IIFE (Immediately Invoked Function Expression)
     * to isolate variable declarations while reusing a single context.
     *
     * This approach solves the memory bloat issue where each evaluateScript() call
     * previously created a new GraalJS context (~1MB each), causing OOM errors in
     * flows with 1000+ iterations.
     *
     * The IIFE wrapper uses `eval()` internally to:
     * - Scope variables (var/let/const) to the function
     * - Return the value of the last expression (eval's natural behavior)
     * - Keep shared bindings (output, maestro) accessible and persistent
     */
    private fun evalWithIIFE(script: String, sourceName: String): Value {
        val context = getOrCreateContext()
        syncBindingsToContext(context)

        // Wrap script in IIFE with eval() to:
        // 1. Isolate variable declarations to the function scope
        // 2. Return the last expression's value (eval's behavior)
        // We use a template literal to safely embed the script.
        val escapedScript = script
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("\${", "\\\${")
        val wrappedScript = "(function(){ return eval(`$escapedScript`) })()"
        val source = Source.newBuilder("js", wrappedScript, sourceName).build()
        return context.eval(source)
    }

    /**
     * Syncs all bindings to the context. All binding management happens here.
     */
    private fun syncBindingsToContext(context: Context) {
        val bindings = context.getBindings("js")

        // Set static bindings if not yet set
        if (!bindings.hasMember("http")) {
            maestroBinding["platform"] = platform
            bindings.putMember("http", httpBinding)
            bindings.putMember("faker", faker)
            bindings.putMember("output", ProxyObject.fromMap(outputBinding))
            bindings.putMember("maestro", ProxyObject.fromMap(maestroBinding))
        }

        // Clear non-permanent (env) bindings, then set current env vars
        bindings.memberKeys
            .filter { it !in permanentBindingKeys }
            .forEach { bindings.removeMember(it) }
        envBinding.forEach { (k, v) -> bindings.putMember(k, v) }
    }

    val hostAccess = HostAccess.newBuilder()
        .allowAccessAnnotatedBy(HostAccess.Export::class.java)
        .allowAllPublicOf(Faker::class.java)
        .build()

    /**
     * Returns the shared context, creating it lazily on first access.
     * This ensures we only ever have one context, avoiding memory bloat.
     */
    private fun getOrCreateContext(): Context {
        sharedContext?.let { return it }

        val outputStream = object : ByteArrayOutputStream() {
            override fun flush() {
                super.flush()
                val log = toByteArray().decodeToString().removeSuffix("\n")
                onLogMessage(log)
                reset()
            }
        }

        val context = Context.newBuilder("js")
            .option("js.strict", "true")
            .logHandler(NULL_HANDLER)
            .out(outputStream)
            .allowHostAccess(hostAccess)
            .build()

        context.eval(
            "js", """
            // Prevent a reference error on referencing undeclared variables. Enables patterns like {MY_ENV_VAR || 'default-value'}.
            // Instead of throwing an error, undeclared variables will evaluate to undefined.
            Object.setPrototypeOf(globalThis, new Proxy(Object.prototype, {
                has(target, key) {
                    return true;
                }
            }))
            function json(text) {
                return JSON.parse(text)
            }
            function relativePoint(x, y) {
                var xPercent = Math.ceil(x * 100) + '%'
                var yPercent = Math.ceil(y * 100) + '%'
                return xPercent + ',' + yPercent
            }
        """.trimIndent()
        )

        sharedContext = context
        return context
    }

    override fun enterEnvScope() {
        // Create a new environment variable scope for flow isolation.
        // For GraalJS, we manually manage environment variable scoping by
        // saving the current environment state to a stack before allowing
        // new variables to be added or existing ones to be overridden.
        envScopeStack.add(HashMap(envBinding))
    }

    override fun leaveEnvScope() {
        // Restore previous environment state
        if (envScopeStack.isNotEmpty()) {
            val previousEnv = envScopeStack.removeAt(envScopeStack.size - 1)
            envBinding.clear()
            envBinding.putAll(previousEnv)
        }
    }

    private fun HostAccess.Builder.allowAllPublicOf(clazz: Class<*>): HostAccess.Builder {
        if (clazz in fakerPublicClasses) return this
        fakerPublicClasses.add(clazz)
        clazz.methods.filter {
            it.declaringClass != Object::class.java &&
                    it.declaringClass != AbstractProvider::class.java &&
                    java.lang.reflect.Modifier.isPublic(it.modifiers)
        }.forEach { method ->
            allowAccess(method)
            if (AbstractProvider::class.java.isAssignableFrom(method.returnType) && !fakerPublicClasses.contains(method.returnType)) {
                allowAllPublicOf(method.returnType)
            }
        }
        return this
    }
}
