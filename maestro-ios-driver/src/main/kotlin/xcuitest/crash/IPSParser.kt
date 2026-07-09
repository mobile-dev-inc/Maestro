/**
 * Parser for iOS crash report files in .ips format.
 * Extracts crash information including exception type, signal, and simulator UUID.
 */
package xcuitest.crash

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

object IPSParser {

    private val mapper = jacksonObjectMapper()
    private val SIMULATOR_COALITION_PREFIX = "com.apple.CoreSimulator.SimDevice."
    private val timestampFormatter: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("uuuu-MM-dd HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true)
        .appendLiteral(' ')
        .appendOffset("+HHmm", "Z")
        .toFormatter()

    /**
     * Parsed crash information from an IPS file.
     *
     * @param simulatorId The simulator UUID extracted from coalitionName, or null if not a simulator crash
     * @param bundleId The app's bundle ID
     * @param processName The crashed process name
     * @param exceptionType The exception type (e.g., EXC_CRASH, EXC_BREAKPOINT, EXC_BAD_ACCESS)
     * @param signal The signal that caused the crash (e.g., SIGKILL, SIGTRAP, SIGSEGV)
     * @param exceptionSubtype For EXC_BAD_ACCESS: memory address info (e.g., "KERN_INVALID_ADDRESS at 0x...")
     * @param terminationReason The termination indicator if present (e.g., "Abort trap: 6")
     */
    data class ParsedCrash(
        val simulatorId: String?,
        val bundleId: String?,
        val processName: String,
        val exceptionType: String,
        val signal: String,
        val exceptionSubtype: String?,
        val terminationReason: String?,
    ) {
        /** Crash time from the IPS header, or null when absent/malformed. */
        var timestampEpochMs: Long? = null
            internal set

        /**
         * Returns a user-friendly message describing the crash.
         */
        val friendlyMessage: String
            get() = buildString {
                append("$exceptionType ($signal)")
                // For EXC_BAD_ACCESS, the subtype is more informative than termination reason
                val detail = exceptionSubtype ?: terminationReason
                detail?.let { append(" - $it") }
            }
    }

    /**
     * Parse IPS file content into crash information.
     *
     * IPS files have a multi-object JSON format:
     * - Line 1: Compact JSON header with app_name, bundleID, timestamp, etc.
     * - Lines 2+: Pretty-printed JSON body with exception, termination, threads, etc.
     *
     * @param content The raw IPS file content
     * @return Parsed crash info, or null if parsing fails or no exception info found
     */
    fun parse(content: String): ParsedCrash? {
        if (content.isBlank()) return null

        return try {
            val jsonObjects = parseMultiObjectJson(content)
            if (jsonObjects.isEmpty()) return null

            // Merge all JSON objects into one map
            val allData = mutableMapOf<String, Any>()
            jsonObjects.forEach { allData.putAll(it) }

            // Extract required fields
            val exception = allData["exception"] as? Map<*, *> ?: return null
            val exceptionType = exception["type"] as? String ?: return null
            val signal = exception["signal"] as? String ?: return null

            // Extract exception subtype (for EXC_BAD_ACCESS memory errors)
            val exceptionSubtype = exception["subtype"] as? String

            // Extract process name
            val processName = allData["procName"] as? String
                ?: allData["app_name"] as? String
                ?: return null

            // Extract bundle ID from header or bundleInfo
            val bundleId = allData["bundleID"] as? String
                ?: (allData["bundleInfo"] as? Map<*, *>)?.get("CFBundleIdentifier") as? String

            val timestampEpochMs = (allData["timestamp"] as? String)
                ?.let(::parseTimestampEpochMs)

            // Extract simulator UUID from coalitionName
            val coalitionName = allData["coalitionName"] as? String
            val simulatorId = coalitionName?.let { extractSimulatorId(it) }

            // Extract termination reason if present
            val termination = allData["termination"] as? Map<*, *>
            val terminationReason = termination?.get("indicator") as? String

            ParsedCrash(
                simulatorId = simulatorId,
                bundleId = bundleId,
                processName = processName,
                exceptionType = exceptionType,
                signal = signal,
                exceptionSubtype = exceptionSubtype,
                terminationReason = terminationReason,
            ).also { it.timestampEpochMs = timestampEpochMs }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseTimestampEpochMs(value: String): Long? = runCatching {
        OffsetDateTime.parse(value, timestampFormatter).toInstant().toEpochMilli()
    }.getOrNull()

    /**
     * Parse the IPS file content into separate JSON objects.
     *
     * IPS files contain multiple JSON objects concatenated together.
     * The first line is typically a compact header, followed by a pretty-printed body.
     * We use brace counting to identify object boundaries.
     */
    private fun parseMultiObjectJson(content: String): List<Map<String, Any>> {
        val lines = content.lines()
        val objects = mutableListOf<Map<String, Any>>()
        val stringBuilder = StringBuilder()
        var braceCount = 0

        for (line in lines) {
            stringBuilder.append(line)

            braceCount += line.count { it == '{' }
            braceCount -= line.count { it == '}' }

            if (braceCount == 0 && stringBuilder.isNotEmpty()) {
                val jsonStr = stringBuilder.toString().trim()
                if (jsonStr.isNotEmpty()) {
                    try {
                        objects.add(mapper.readValue(jsonStr))
                    } catch (e: Exception) {
                        // Skip malformed JSON objects
                    }
                }
                stringBuilder.clear()
            }
        }

        return objects
    }

    /**
     * Extract the simulator UUID from a coalitionName.
     *
     * @param coalitionName e.g., "com.apple.CoreSimulator.SimDevice.AE09D900-B27D-4AC8-8F69-470FC52AC0D2"
     * @return The UUID portion, or null if not a simulator coalition
     */
    private fun extractSimulatorId(coalitionName: String): String? {
        return if (coalitionName.startsWith(SIMULATOR_COALITION_PREFIX)) {
            coalitionName.removePrefix(SIMULATOR_COALITION_PREFIX)
        } else {
            null
        }
    }
}
