package maestro.orchestra.util

import java.io.File
import maestro.js.JsEngine
import maestro.orchestra.DefineVariablesCommand
import maestro.orchestra.MaestroCommand

object Env {

    fun String.evaluateScripts(jsEngine: JsEngine): String {
        val result = "(?<!\\\\)\\\$\\{([^\$]*)}".toRegex()
            .replace(this) { match ->
                val script = match.groups[1]?.value ?: ""

                if (script.isNotBlank()) {
                    jsEngine.evaluateScript(script).toString()
                } else {
                    ""
                }
            }

        return result
            .replace("\\\\\\\$\\{([^\$]*)}".toRegex()) { match ->
                match.value.substringAfter('\\')
            }
    }

    fun List<String>.evaluateScripts(jsEngine: JsEngine): List<String> =
        map { it.evaluateScripts(jsEngine) }

    /**
     * Interpolates both the keys and the values of a string-keyed map. Values that aren't
     * strings (e.g. the booleans and numbers allowed in `launchArguments`) pass through
     * untouched.
     *
     * Because keys are interpolated, two literal keys can collapse into one — `${P}: allow`
     * alongside `location: deny` with `P=location`. YAML rejects literal duplicates, so this
     * is only reachable through interpolation, and silently keeping the last value would hide
     * the mistake; [DuplicateKeyError] is raised instead.
     *
     * [description] names the map in error messages, e.g. "permissions".
     *
     * Use [evaluateValueScripts] where the key is a declaration site rather than user data.
     */
    fun <V> Map<String, V>.evaluateScripts(jsEngine: JsEngine, description: String): Map<String, V> {
        if (isEmpty()) return this
        requireNoNullValues(description)

        val result = LinkedHashMap<String, V>(size)
        forEach { (key, value) ->
            val evaluatedKey = key.evaluateScripts(jsEngine)
            if (result.containsKey(evaluatedKey)) throw DuplicateKeyError(description, evaluatedKey)
            result[evaluatedKey] = value.evaluateValueScript(jsEngine)
        }
        return result
    }

    /**
     * Interpolates the values of a string-keyed map, leaving the keys verbatim. Values that
     * aren't strings pass through untouched.
     *
     * This is the right choice where the key names something being defined rather than
     * carrying user data — an `env` block declares variable names, so interpolating them
     * would produce a variable nothing can reference. See [evaluateScripts] for the
     * both-sides variant.
     */
    fun <V> Map<String, V>.evaluateValueScripts(jsEngine: JsEngine, description: String): Map<String, V> {
        if (isEmpty()) return this
        requireNoNullValues(description)
        return mapValues { (_, value) -> value.evaluateValueScript(jsEngine) }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <V> V.evaluateValueScript(jsEngine: JsEngine): V =
        if (this is String) evaluateScripts(jsEngine) as V else this

    private fun Map<String, *>.requireNoNullValues(description: String) {
        val nullKeys = nullValuedKeys()
        if (nullKeys.isNotEmpty()) throw MissingValueError(description, nullKeys)
    }

    /**
     * Jackson's KotlinModule lets nulls into maps declared with non-null value types (generic
     * erasure), so a YAML entry like `KEY:` with no value lands here as null and would later
     * crash interpolation with an opaque Kotlin null-intrinsics error. Callers use this to
     * fail fast, naming every offending key so the user knows exactly what to fix.
     */
    private fun Map<String, *>.nullValuedKeys(): Set<String> = filterValues { it == null }.keys

    fun List<MaestroCommand>.withEnv(env: Map<String, String>): List<MaestroCommand> {
        if (env.isEmpty()) return this
        val nullKeys = env.nullValuedKeys()
        if (nullKeys.isNotEmpty()) throw EnvVariableMissingValueError(nullKeys)
        return listOf(MaestroCommand(DefineVariablesCommand(env))) + this
    }

    /**
     * Raised by [withEnv] when the env map carries one or more null values. Caught by
     * `MaestroFlowParser.wrapException` so the rendered error names the missing key(s)
     * instead of surfacing a generic "Parsing Failed" summary.
     */
    class EnvVariableMissingValueError(val keys: Set<String>) : IllegalArgumentException(
        "Environment variable(s) ${keys.joinToString(", ")} have no value. " +
            "Set an explicit value (e.g. `${keys.first()}: \"\"` for an empty string) " +
            "or remove the key."
    )

    /**
     * Raised when a map reaching interpolation carries null values — see [nullValuedKeys].
     */
    class MissingValueError(val description: String, val keys: Set<String>) : IllegalArgumentException(
        if (keys.size == 1) {
            "$description entry \"${keys.first()}\" has no value. "
        } else {
            "$description entries ${keys.joinToString(", ") { "\"$it\"" }} have no value. "
        } + "Set an explicit value (e.g. `${keys.first()}: \"\"` for an empty string) or remove the key."
    )

    /**
     * Raised when interpolating a map's keys collapses two of them into one — see [evaluateScripts].
     */
    class DuplicateKeyError(val description: String, val key: String) : IllegalArgumentException(
        "$description has more than one entry for \"$key\" after interpolation. " +
            "Two keys resolved to the same name; rename or remove one."
    )

    /**
     * Reserved internal env vars that are controlled exclusively by Maestro.
     * These cannot be set externally via --env, flow env, or shell environment.
     * Any external values will be stripped and replaced by internal logic.
     */
    private val INTERNAL_ONLY_ENV_VARS = setOf(
        "MAESTRO_SHARD_ID",
        "MAESTRO_SHARD_INDEX",
    )

    fun Map<String, String>.withInjectedShellEnvVars(): Map<String, String> = this +
        System.getenv()
            .filterKeys {
                it.startsWith("MAESTRO_") &&
                    this.containsKey(it).not() &&
                    it !in INTERNAL_ONLY_ENV_VARS
            }
            .filterValues { it != null && it.isNotEmpty() }

    fun Map<String, String>.withDefaultEnvVars(
        flowFile: File? = null,
        deviceId: String? = null,
        shardIndex: Int? = null,
    ): Map<String, String> {
        val defaultEnvVars = mutableMapOf<String, String>()
        flowFile?.nameWithoutExtension?.let { defaultEnvVars["MAESTRO_FILENAME"] = it }
        deviceId?.takeIf { it.isNotBlank() }?.let { defaultEnvVars["MAESTRO_DEVICE_UDID"] = it }
        // Always set shard vars - use actual values if sharding, otherwise defaults (1, 0)
        // This ensures flows using these vars don't fail with undefined when debugging in Studio
        val effectiveShardIndex = shardIndex ?: 0
        defaultEnvVars["MAESTRO_SHARD_ID"] = (effectiveShardIndex + 1).toString()
        defaultEnvVars["MAESTRO_SHARD_INDEX"] = effectiveShardIndex.toString()
        // Start with base map, removing any existing shard vars to prevent external pollution
        val baseMap = this - INTERNAL_ONLY_ENV_VARS
        return baseMap + defaultEnvVars
    }
}
