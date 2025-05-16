package maestro.js

import maestro.utils.HttpClient
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.io.FileSystem
import org.graalvm.polyglot.proxy.ProxyObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.nio.file.Paths
import java.nio.file.Path
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

    private val openContexts = HashSet<Context>()

    private val httpBinding = GraalJsHttp(httpClient)
    private val outputBinding = HashMap<String, Any>()
    private val maestroBinding = HashMap<String, Any?>()
    private val envBinding = HashMap<String, String>()

    private var onLogMessage: (String) -> Unit = {}

    private var platform = platform

    override fun close() {
        openContexts.forEach { it.close() }
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
        envBinding.putAll(env)
        val source = Source.newBuilder("js", script, sourceName).build()
        val sourceDir = getSourceDirectory(sourceName)
        return createContext(sourceDir).eval(source)
    }

    private fun getSourceDirectory(sourceName: String): String {
        return envBinding["MAESTRO_YAML_DIR"] ?: run {
            val path = resolveScriptPath(sourceName)
            val parent = path.parent
            return parent?.toString() ?: System.getProperty("user.dir")
        }
    }
    
    private fun resolveScriptPath(sourceName: String): Path {
        val path = Paths.get(sourceName)
        return if (path.isAbsolute) path else Paths.get(System.getProperty("user.dir"), sourceName)
    }

    private fun createContext(moduleDir: String): Context {
        val outputStream = object : ByteArrayOutputStream() {
            override fun flush() {
                super.flush()
                val log = toByteArray().decodeToString().removeSuffix("\n")
                onLogMessage(log)
                reset()
            }
        }

        val secureFileSystem = GraalJsSecurityFileSystem(moduleDir)

        val context = Context.newBuilder("js")
            .option("js.strict", "true")
            .option("js.commonjs-require", "true")
            .option("js.commonjs-require-cwd", moduleDir)
            .allowExperimentalOptions(true)
            .allowIO(true)
            .fileSystem(secureFileSystem)
            .allowHostAccess(HostAccess.newBuilder()
                .allowPublicAccess(true)
                .build())
            .allowHostClassLookup { false }
            .logHandler(NULL_HANDLER)
            .out(outputStream)
            .build()

        openContexts.add(context)

        envBinding.forEach { (key, value) -> context.getBindings("js").putMember(key, value) }

        context.getBindings("js").putMember("http", httpBinding)
        context.getBindings("js").putMember("output", ProxyObject.fromMap(outputBinding))
        context.getBindings("js").putMember("maestro", ProxyObject.fromMap(maestroBinding))


        maestroBinding["platform"] = platform

        context.eval(
            "js", """
            // Prevent a reference error on referencing undeclared variables
            Object.setPrototypeOf(globalThis, new Proxy(Object.prototype, {
                has(target, key) {
                    return true;
                }
            }));
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

        return context
    }
}