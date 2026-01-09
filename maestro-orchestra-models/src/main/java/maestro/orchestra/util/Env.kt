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

    fun List<MaestroCommand>.withEnv(env: Map<String, String>): List<MaestroCommand> =
        if (env.isEmpty()) this
        else listOf(MaestroCommand(DefineVariablesCommand(env))) + this

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
