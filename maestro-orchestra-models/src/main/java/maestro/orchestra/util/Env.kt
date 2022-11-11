package maestro.orchestra.util

object Env {

    fun String.injectEnv(env: Map<String, String>): String {
        if (env.isEmpty()) {
            return this
        }

        return env
            .entries
            .fold(this) { acc, (key, value) ->
                acc.replace(
                    "(?<!\\\\)\\$\\{$key}".toRegex(),
                    Regex.escapeReplacement(value)
                )
            }
            .replace("\\\\\\$\\{.*}".toRegex()) { match ->
                match.value.substringAfter('\\')
            }
    }

}