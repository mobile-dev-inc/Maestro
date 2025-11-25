package maestro

/**
 * Represents a single log entry captured from the device during test execution.
 *
 * @property timestamp Time when the log was generated (format: "HH:mm:ss.SSS")
 * @property pid Process ID that generated the log
 * @property tid Thread ID that generated the log
 * @property level Severity level of the log entry
 * @property tag Component or category tag for the log
 * @property message The log message content
 */
data class LogEntry(
    val timestamp: String,
    val pid: Int,
    val tid: Int,
    val level: LogLevel,
    val tag: String,
    val message: String
)

/**
 * Log severity levels, ordered from least to most severe.
 */
enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ASSERT
}
