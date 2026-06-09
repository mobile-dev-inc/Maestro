/**
 * Android logcat helpers for crash/ANR collection.
 *
 * Ported from the cloud worker's shared library so Maestro core owns device-log
 * capture (see docs/superpowers/specs/2026-06-09-device-logs-under-orchestra-design.md).
 */
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

/**
 * ActivityManager logcat lines from the main buffer (Debug level and above),
 * used for parsing ANR events. Null if the command fails.
 */
fun Dadb.getActivityManagerLogs(): String? {
    return runCatching {
        shell("logcat -v time -b main -s ActivityManager:D -d").output
    }.getOrNull()
}
