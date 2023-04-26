package maestro.orchestra.filter

import maestro.MaestroException

object LaunchArguments {

    private const val MAX_LAUNCH_ARGUMENT_PAIRS_ALLOWED = 1

    fun List<String>?.toSanitizedLaunchArguments(appId: String): List<String> {
        if (isNullOrEmpty()) return emptyList()

        return map {
            val allowedPairs = it.filter { char -> char == '=' }.length

            val message = "Unable to launch app $appId, launch arguments can either have one key/value " +
                "pair or a boolean key. Look documentation for more details."
            if (allowedPairs > MAX_LAUNCH_ARGUMENT_PAIRS_ALLOWED) throw MaestroException.UnableToLaunchApp(message)

            return@map if (it.contains("=")) {
                it.split("=").joinToString(separator = "=") { param -> param.trim() }
            } else {
                it
            }
        }
    }
}