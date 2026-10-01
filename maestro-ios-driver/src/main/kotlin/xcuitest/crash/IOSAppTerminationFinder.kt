package xcuitest.crash

import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * How an app's process ended on a simulator, as the simulator's process manager (runningboardd)
 * recorded it: `termination reported by launchd (<domain>, <code>, <signal>)`.
 */
data class AppTermination(val pid: Int, val domain: Int, val code: Long) {

    /**
     * Ended by a fault signal. Not a crash: a normal exit, being closed on purpose (force-quit), or
     * being killed (SIGKILL), which the system also does for reasons other than a fault.
     */
    val isCrash: Boolean get() = domain == SIGNAL_DOMAIN && code in CRASH_SIGNALS

    val signalName: String get() = CRASH_SIGNALS[code] ?: "signal $code"

    val summary: String get() = "App crashed ($signalName)"

    companion object {
        private const val SIGNAL_DOMAIN = 2
        private val CRASH_SIGNALS = mapOf(
            4L to "SIGILL", 5L to "SIGTRAP", 6L to "SIGABRT", 8L to "SIGFPE",
            10L to "SIGBUS", 11L to "SIGSEGV", 12L to "SIGSYS",
        )
    }
}

/**
 * Reads an app's terminations from the simulator's system log. The record is written the moment
 * the process ends, so a crash is known at once, without waiting for the crash report file.
 *
 * @param readLog the simulator's runningboardd termination lines since an epoch-millis instant
 */
class IOSAppTerminationFinder(
    private val readLog: (simulatorId: String, sinceEpochMs: Long) -> String = ::readTerminationLog,
) {

    /** Terminations of [bundleId] since [sinceEpochMs], oldest first. Null when the log can't be read. */
    fun find(simulatorId: String, bundleId: String, sinceEpochMs: Long): List<AppTermination>? {
        val log = try {
            readLog(simulatorId, sinceEpochMs)
        } catch (e: Exception) {
            logger.warn("Could not read app terminations from simulator $simulatorId", e)
            return null
        }
        // `[app<bundle(...)>:pid] termination …` is the app itself; its helper processes are logged as
        // `[xpcservice<…([app<bundle(...)>:pid])>…:otherPid] termination …` and do not match.
        val line = """\[app<${Regex.escape(bundleId)}\(.*?\)>:(\d+)] termination reported by launchd \((\d+), (\d+), \d+\)""".toRegex()
        return line.findAll(log)
            .map { AppTermination(pid = it.groupValues[1].toInt(), domain = it.groupValues[2].toInt(), code = it.groupValues[3].toLong()) }
            .toList()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(IOSAppTerminationFinder::class.java)
        private val LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())
        private const val LOG_TIMEOUT_SECONDS = 30L

        private fun readTerminationLog(simulatorId: String, sinceEpochMs: Long): String {
            val process = ProcessBuilder(
                "xcrun", "simctl", "spawn", simulatorId, "log", "show",
                "--start", LOG_TIME.format(Instant.ofEpochMilli(sinceEpochMs)),
                "--style", "compact",
                "--predicate", """process == "runningboardd" AND eventMessage CONTAINS "termination reported by launchd"""",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            check(process.waitFor(LOG_TIMEOUT_SECONDS, TimeUnit.SECONDS) && process.exitValue() == 0) {
                "log show failed on simulator $simulatorId: ${output.take(200)}"
            }
            return output
        }
    }
}
