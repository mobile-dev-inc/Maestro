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

    fun Map<String, String>.withInjectedShellEnvVars(): Map<String, String> = this +
        System.getenv()
            .filterKeys { it.startsWith("MAESTRO_") && this.containsKey(it).not() }
            .filterValues { it != null && it.isNotEmpty() }

    fun Map<String, String>.withDefaultEnvVars(flowFile: File? = null): Map<String, String> {
        val defaultEnvVars = mutableMapOf<String, String>()
        flowFile?.nameWithoutExtension?.let { defaultEnvVars["MAESTRO_FILENAME"] = it }
        return if (defaultEnvVars.isEmpty()) this
        else this + defaultEnvVars
    }
}
