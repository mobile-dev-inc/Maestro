package maestro.js

import org.graalvm.polyglot.PolyglotException

/**
 * Plain-data view of a JavaScript evaluation error. All fields are JVM-safe
 * (String / List<String> / Boolean) and resolved while the originating Polyglot
 * engine is still open, so this object can outlive the engine and survive
 * downstream serialization (Jackson, etc.) without re-entering the closed runtime.
 */
data class JsScriptError(
    val message: String,
    val causeMessage: String?,
    val sourceClass: String,
    val stackFrames: List<String>,
    val isHostException: Boolean,
    val isGuestException: Boolean,
)

/**
 * Thrown by [JsEngine.evaluateScript] when script evaluation fails. Replaces the
 * raw [PolyglotException] previously propagated by the GraalJS implementation,
 * so callers — and any downstream code that retains exceptions on result models —
 * never observe live polyglot internals.
 */
class JsEvaluationException(val error: JsScriptError) : RuntimeException(error.message)

/**
 * Convert a [PolyglotException] into a fully detached [JsScriptError]. Every
 * field is resolved to a plain JVM type before this returns; after the call,
 * the original exception can be released and the engine closed without
 * affecting the resulting object.
 */
fun PolyglotException.toJsScriptError(): JsScriptError = JsScriptError(
    message = this.message ?: "(no message)",
    causeMessage = this.cause?.message,
    sourceClass = this::class.java.name,
    stackFrames = runCatching {
        this.polyglotStackTrace.map { frame ->
            val lang = frame.language?.id ?: "?"
            val name = frame.rootName ?: "?"
            val loc = frame.sourceLocation?.toString() ?: "?"
            "$lang $name ($loc)"
        }
    }.getOrElse { emptyList() },
    isHostException = this.isHostException,
    isGuestException = this.isGuestException,
)
