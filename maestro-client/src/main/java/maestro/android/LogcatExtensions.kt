/** Android logcat helpers for crash/ANR collection. */
package maestro.android

/** Recent crash-buffer logcat lines (JVM + native crashes, all apps), or null if the command fails. */
fun AndroidDeviceConnection.getCrashLogs(): String? {
    return runCatching {
        shell("logcat -v time -b crash -t 2000 -d").output
    }.getOrNull()
}

/** ActivityManager logcat lines (for ANR parsing), or null if the command fails. */
fun AndroidDeviceConnection.getActivityManagerLogs(): String? {
    return runCatching {
        shell("logcat -v time -b main -s ActivityManager:D -d").output
    }.getOrNull()
}
