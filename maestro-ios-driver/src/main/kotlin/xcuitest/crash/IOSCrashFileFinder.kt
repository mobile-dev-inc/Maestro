package xcuitest.crash

import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Finds iOS crash report files (.ips) in the system's DiagnosticReports directories.
 *
 * iOS simulator crashes are stored on the host machine, not in the simulator filesystem.
 * Crash files may appear in either the user-level or system-level DiagnosticReports
 * directory depending on which user the simulator process runs as.
 *
 * @param crashDirectories List of directories to search for crash files
 */
class IOSCrashFileFinder(
    private val crashDirectories: List<File> = DEFAULT_CRASH_DIRECTORIES
) {

    companion object {
        private val logger = LoggerFactory.getLogger(IOSCrashFileFinder::class.java)

        private const val DEFAULT_POLL_INTERVAL_MS = 500L

        /**
         * Default directories to search for crash reports.
         * Includes both user-level and system-level DiagnosticReports directories
         * since simulators may run as different users.
         */
        val DEFAULT_CRASH_DIRECTORIES = listOf(
            File("${System.getProperty("user.home")}/Library/Logs/DiagnosticReports"),
            File("/Library/Logs/DiagnosticReports")
        )
    }

    /**
     * Find the most recent crash file matching the given simulator and bundle ID.
     *
     * The crash file is matched by:
     * 1. Using grep to find files containing the bundleId (fast)
     * 2. Parsing matches to verify simulatorId in coalitionName
     *
     * Note: We use grep instead of filename matching because app_name (used in filename)
     * often differs from bundleId. E.g., "DasherRed" vs "com.doordash.dasher.RedApp"
     *
     * @param simulatorId The simulator UUID to match against coalitionName
     * @param bundleId The bundle ID to search for (e.g., "com.example.myapp")
     * @return The most recent matching crash file, or null if none found
     */
    fun findCrashFile(simulatorId: String, bundleId: String): File? {
        // Use grep to find .ips files containing the bundleId (fast)
        val candidates = findFilesByBundleId(bundleId)

        if (candidates.isEmpty()) {
            logger.info("Crash detection: crashFile=none, totalForBundle=0, bundleId=$bundleId")
            return null
        }

        // Parse candidates to verify simulatorId
        val matchingFiles = candidates.mapNotNull { file ->
            try {
                val content = file.readText()
                val parsed = IPSParser.parse(content)
                if (parsed?.simulatorId == simulatorId) file else null
            } catch (e: Exception) {
                null
            }
        }

        // Return most recent by modification time
        val result = matchingFiles.maxByOrNull { it.lastModified() }

        val crashFileName = result?.name ?: "none"
        logger.info("Crash detection: crashFile=$crashFileName, totalForBundle=${candidates.size}, bundleId=$bundleId")

        return result
    }

    /**
     * Poll [findCrashFile] until a report newer than [sinceEpochMs] appears or [timeoutMs] elapses —
     * iOS writes the .ips asynchronously, so a single lookup at flow end can miss it.
     * [timeoutMs] <= 0 checks exactly once.
     */
    fun waitForCrashFile(
        simulatorId: String,
        bundleId: String,
        sinceEpochMs: Long,
        timeoutMs: Long,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
    ): File? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            findCrashFile(simulatorId, bundleId)
                ?.takeIf { it.lastModified() >= sinceEpochMs }
                ?.let { return it }

            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return null
            Thread.sleep(minOf(pollIntervalMs, remaining))
        }
    }

    /**
     * Use grep to find .ips files containing the given bundleId.
     * This is much faster than reading/parsing each file.
     */
    private fun findFilesByBundleId(bundleId: String): List<File> {
        val directories = crashDirectories
            .filter { it.exists() && it.isDirectory }
            .flatMap { dir -> dir.listFiles { f -> f.extension == "ips" }?.toList() ?: emptyList() }

        if (directories.isEmpty()) {
            return emptyList()
        }

        return try {
            val searchPattern = """"bundleID":"$bundleId""""
            val command = listOf("grep", "-l", searchPattern) + directories.map { it.absolutePath }

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(30, TimeUnit.SECONDS)

            if (process.exitValue() == 0) {
                output.lines()
                    .filter { it.isNotBlank() }
                    .map { File(it) }
                    .filter { it.exists() }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
