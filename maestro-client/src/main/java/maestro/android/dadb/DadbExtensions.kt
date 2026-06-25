/** Android logcat helpers for crash/ANR collection. */
package maestro.android.dadb

import dadb.Dadb
import java.util.Locale

/** Recent crash-buffer logcat lines mentioning [packageId], or null if the command fails. */
fun Dadb.getAppCrashLogs(packageId: String): String? {
    return runCatching {
        shell(
            String.format(
                Locale.US,
                "logcat -v time -b crash -e \"%s\" -t 20 -d",
                packageId
            )
        ).output
    }.getOrNull()
}

/** ActivityManager logcat lines (for ANR parsing), or null if the command fails. */
fun Dadb.getActivityManagerLogs(): String? {
    return runCatching {
        shell("logcat -v time -b main -s ActivityManager:D -d").output
    }.getOrNull()
}
