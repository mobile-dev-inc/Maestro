/**
 * Parser for Android logcat output.
 * Extracts crash and ANR information from logcat logs.
 *
 * Shared implementation used by both backend and maestro-worker.
 */
package maestro.android.crashes

import java.io.BufferedReader
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object LogcatReader {

    private val logcatDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    // regex patterns for logcat lines
    private val DATE_TIME = Pattern.compile("\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d\\.\\d\\d\\d") // 02-28 17:14:25.625
    private val FATAL = Pattern.compile("FATAL EXCEPTION:")
    private val PROCESS = Pattern.compile("Process:\\s") // Process: com.example.app.alpha, PID: 28715
    private val FILTERED_LINE =
        Pattern.compile("(?!.+\\)\\:)\\s.+") // matches line without metadata info (date, tag, process etc..)

    // ANR patterns - matches ActivityManager ANR output
    // Example: "12-10 21:31:57.981 E/ActivityManager(  539): ANR in br.com.quintoandar.inquilinos.forno"
    // Example end: "12-10 21:31:57.987 D/ActivityManager(  539): Completed ANR of br.com.quintoandar.inquilinos.forno in 3408ms"
    private val ANR_IN = Pattern.compile("ANR in (\\S+)")
    private val ANR_COMPLETED = Pattern.compile("Completed ANR of (\\S+)")
    private val ANR_PID = Pattern.compile("PID: (\\d+)")
    private val ANR_REASON = Pattern.compile("Reason: (.+)")

    // Max raw log size to prevent excessive memory usage (32KB)
    private const val MAX_RAW_LOG_SIZE = 32 * 1024

    /**
     * Parses logcat output that contains only FATAL EXCEPTION(s)
     *
     * @param data logcat output
     * @return crash report
     */
    private fun findCrashes(data: BufferedReader): LogcatCrashReport {
        val crashes = mutableListOf<LogcatCrashReport.Crash>()
        var isProcessLine: Boolean = false
        data.forEachLine {
            val header = FATAL.matcher(it).let { matcher ->
                if (matcher.find()) matcher.group()
                else null
            }

            if (header != null) {
                val date = DATE_TIME.matcher(it).let { matcher ->
                    if (matcher.find()) parseTime(matcher.group())
                    else null
                }
                if (date != null) crashes.add(LogcatCrashReport.Crash(date, header))
            } else if (crashes.isNotEmpty()) {
                val crash = crashes.last()
                crash.stackTrace += "$it\n"

                if (isProcessLine) { // previous line contains "process"
                    isProcessLine = false
                    val cause = FILTERED_LINE.matcher(it).let { matcher ->
                        if (matcher.find()) matcher.group()
                        else null
                    }
                    if (cause != null) crash.cause = cause.trimStart()
                } else if (crash.cause.isEmpty()) {
                    isProcessLine = PROCESS.matcher(it).find()
                }
            }
        }

        return LogcatCrashReport(crashes)
    }

    fun findCrashes(data: String): LogcatCrashReport =
        findCrashes(data.byteInputStream().bufferedReader(Charsets.UTF_8))

    /**
     * Parses logcat output for ANR (Application Not Responding) events.
     * ANRs are logged by ActivityManager in the main buffer with format:
     * Start: "ANR in <packageId>"
     * Body: "PID: <pid>", "Reason: <reason>", etc.
     * End: "Completed ANR of <packageId>"
     *
     * Only captures logs between the start and end markers for each ANR.
     *
     * @param data logcat output from main buffer filtered for ANR entries
     * @return ANR report containing all parsed ANR events
     */
    private fun findANRs(data: BufferedReader): LogcatANRReport {
        val anrs = mutableListOf<LogcatANRReport.ANR>()
        var currentAnr: LogcatANRReport.ANR? = null
        var currentRawLogLength = 0

        data.forEachLine { line ->
            // Check for "Completed ANR of <package>" end marker
            val completedMatch = ANR_COMPLETED.matcher(line)
            if (completedMatch.find() && currentAnr != null) {
                val completedPackage = completedMatch.group(1)
                if (completedPackage == currentAnr?.packageId) {
                    // Include the completion line in raw log (if within limit)
                    currentAnr = currentAnr?.let { anr ->
                        if (currentRawLogLength < MAX_RAW_LOG_SIZE) {
                            anr.copy(rawLog = anr.rawLog + line + "\n")
                        } else anr
                    }
                    // Finalize this ANR
                    currentAnr?.let { anrs.add(it) }
                    currentAnr = null
                    currentRawLogLength = 0
                    return@forEachLine
                }
            }

            // Check for "ANR in <package>" start marker
            val anrMatch = ANR_IN.matcher(line)
            if (anrMatch.find()) {
                // If we have an incomplete ANR (no end marker found), save it anyway
                currentAnr?.let { anrs.add(it) }

                // Start new ANR entry
                val packageId = anrMatch.group(1)
                val date = DATE_TIME.matcher(line).let { matcher ->
                    if (matcher.find()) parseTime(matcher.group())
                    else null
                }

                currentAnr = if (date != null) {
                    currentRawLogLength = line.length + 1
                    LogcatANRReport.ANR(
                        date = date,
                        packageId = packageId,
                        rawLog = line + "\n"
                    )
                } else {
                    currentRawLogLength = 0
                    null
                }
            } else if (currentAnr != null) {
                // Look for PID line
                val pidMatch = ANR_PID.matcher(line)
                if (pidMatch.find()) {
                    currentAnr = currentAnr?.copy(pid = pidMatch.group(1).toIntOrNull())
                }

                // Look for Reason line
                val reasonMatch = ANR_REASON.matcher(line)
                if (reasonMatch.find()) {
                    currentAnr = currentAnr?.copy(reason = reasonMatch.group(1))
                }

                // Accumulate raw log (with size limit)
                if (currentRawLogLength < MAX_RAW_LOG_SIZE) {
                    currentAnr = currentAnr?.let { anr ->
                        currentRawLogLength += line.length + 1
                        anr.copy(rawLog = anr.rawLog + line + "\n")
                    }
                }
            }
        }

        // If we have an incomplete ANR at the end (no "Completed" marker), save it
        currentAnr?.let { anrs.add(it) }

        return LogcatANRReport(anrs)
    }

    fun findANRs(data: String): LogcatANRReport =
        findANRs(data.byteInputStream().bufferedReader(Charsets.UTF_8))

    /**
     * Parse the timestamp and return a [Date].  If year is not set, the current year will be
     * used.
     *
     * @param timeStr The timestamp in the format `MM-dd HH:mm:ss.SSS`.
     * @return The [Date].
     */
    private fun parseTime(timeStr: String): Date? {
        val year = Calendar.getInstance().get(Calendar.YEAR)
        return try {
            logcatDateFormat.parse(java.lang.String.format("%s-%s", year, timeStr))
        } catch (e: ParseException) {
            null
        }
    }
}

data class LogcatCrashReport(val crashes: List<Crash>) {

    /**
     * @param timeSpan Filters crashes by time span
     * @return Last crash by date within specified time span
     */
    fun getLastCrash(timeSpan: TimeAgo? = DEFAULT_TIME_AGO): Crash? {
        if (timeSpan != null) {
            val startDate = timeSpan.toDate()
            return crashes.filter { it.date > startDate }.maxByOrNull { it.date }
        }
        return crashes.maxByOrNull { it.date }
    }

    data class TimeAgo(val time: Long, val unit: TimeUnit) {
        fun toDate(): Date = Date(Date().time - unit.toMillis(time))
    }

    data class Crash(
        val date: Date,
        val name: String,
    ) {
        var cause: String = ""
        var stackTrace: String = ""
    }

    companion object {
        val DEFAULT_TIME_AGO = TimeAgo(5, TimeUnit.MINUTES)
    }
}

/**
 * Report containing parsed ANR (Application Not Responding) events from logcat.
 */
data class LogcatANRReport(val anrs: List<ANR>) {

    /**
     * @param packageId Filter to only ANRs for this package
     * @return Last ANR by date for the given package
     */
    fun getLastANR(packageId: String): ANR? {
        return anrs.filter { it.packageId == packageId }.maxByOrNull { it.date }
    }

    /**
     * @return Last ANR by date (any package)
     */
    fun getLastANR(): ANR? {
        return anrs.maxByOrNull { it.date }
    }

    /**
     * Represents a single ANR event.
     *
     * @param date Timestamp when the ANR occurred
     * @param packageId The package that experienced the ANR
     * @param pid Process ID of the ANR (if available)
     * @param reason The reason for the ANR (e.g., "Input dispatching timed out")
     * @param rawLog The raw log output for this ANR
     */
    data class ANR(
        val date: Date,
        val packageId: String,
        val pid: Int? = null,
        val reason: String = "",
        val rawLog: String = ""
    ) {
        /**
         * Returns a user-friendly message describing the ANR.
         */
        val friendlyMessage: String
            get() = if (reason.isNotEmpty()) {
                "ANR: $reason"
            } else {
                "ANR in $packageId"
            }
    }
}
