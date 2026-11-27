package maestro.android

/**
 * Builds the adb shell commands that make app-targeting operations act on a given Android user,
 * such as a work profile, instead of the primary user.
 */
object AndroidUserCommands {

    private const val launcherIntent = "-a android.intent.action.MAIN -c android.intent.category.LAUNCHER"

    // FLAG_ACTIVITY_NEW_TASK, as set by PackageManager.getLaunchIntentForPackage.
    private const val newTaskFlag = "0x10000000"

    fun userFlag(user: Int?): String = user?.let { "--user $it " } ?: ""

    // query-activities, not resolve-activity: with several launcher activities resolve-activity
    // returns the system chooser, while getLaunchIntentForPackage takes the first match.
    fun queryLauncherActivitiesCommand(user: Int, appId: String): String =
        "cmd package query-activities --brief --user $user $launcherIntent $appId"

    fun parseLauncherComponent(queryOutput: String, appId: String): String? =
        queryOutput.lines().map { it.trim() }.firstOrNull { it.startsWith("$appId/") }

    fun amStartCommand(user: Int, component: String, launchArguments: Map<String, Any>): String {
        val extras = launchArguments.map { (key, value) ->
            val flag = when (value) {
                is Boolean -> "--ez"
                is Int -> "--ei"
                is Long -> "--el"
                is Double -> "--ed"
                else -> "--es"
            }
            " $flag ${shellQuote(key)} ${shellQuote(value.toString())}"
        }.joinToString("")
        return "am start --user $user $launcherIntent -f $newTaskFlag -n $component$extras"
    }

    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    // Older Android versions' `am` has no `--ed`, so Double extras cannot be passed from the shell.
    fun isMissingDoubleExtraSupport(errorMessage: String?): Boolean =
        errorMessage?.contains("Unknown option: --ed") == true
}
