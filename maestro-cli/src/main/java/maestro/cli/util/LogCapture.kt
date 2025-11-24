package maestro.cli.util

import maestro.LogEntry
import maestro.LogLevel
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Simple log capture utility using adb logcat.
 * This is a temporary solution until AndroidDriver log capture is fully integrated.
 */
class LogCapture(
    private val deviceId: String? = null,
    private val bufferSize: Int = 5000
) {
    private val logger = LoggerFactory.getLogger(LogCapture::class.java)
    private val logBuffer = ConcurrentLinkedQueue<LogEntry>()
    private val isCapturing = AtomicBoolean(false)
    private var captureProcess: Process? = null
    private var captureThread: Thread? = null

    /**
     * Start capturing logs from the device.
     */
    fun start() {
        if (isCapturing.getAndSet(true)) {
            logger.warn("Log capture already started")
            return
        }

        logger.info("Starting log capture (device: ${deviceId ?: "default"}, bufferSize: $bufferSize)")
        logBuffer.clear()

        try {
            // Build adb logcat command
            val command = buildList {
                add("adb")
                deviceId?.let {
                    add("-s")
                    add(it)
                }
                add("logcat")
                add("-v")
                add("time")  // Format: MM-DD HH:MM:SS.mmm PID TID LEVEL TAG : MESSAGE
            }

            captureProcess = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            // Start thread to read logs
            captureThread = Thread {
                try {
                    BufferedReader(InputStreamReader(captureProcess!!.inputStream)).use { reader ->
                        reader.lineSequence().forEach { line ->
                            if (!isCapturing.get()) return@forEach

                            parseLogLine(line)?.let { entry ->
                                logBuffer.offer(entry)

                                // Remove oldest entry if buffer is full
                                if (logBuffer.size > bufferSize) {
                                    logBuffer.poll()
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isCapturing.get()) {
                        logger.error("Error reading logs", e)
                    }
                }
            }.apply {
                name = "LogCapture-Thread"
                isDaemon = true
                start()
            }

            logger.info("Log capture started successfully")
        } catch (e: Exception) {
            logger.error("Failed to start log capture", e)
            isCapturing.set(false)
            throw e
        }
    }

    /**
     * Stop capturing logs and return all captured entries.
     */
    fun stop(): List<LogEntry> {
        if (!isCapturing.getAndSet(false)) {
            logger.warn("Log capture not active")
            return emptyList()
        }

        logger.info("Stopping log capture")

        try {
            // Stop the process
            captureProcess?.destroy()

            // Wait for thread to finish (with timeout)
            captureThread?.join(2000)

            val logs = logBuffer.toList()
            logger.info("Captured ${logs.size} log entries")
            return logs
        } catch (e: Exception) {
            logger.error("Error stopping log capture", e)
            return logBuffer.toList()
        } finally {
            logBuffer.clear()
            captureProcess = null
            captureThread = null
        }
    }

    /**
     * Get currently buffered logs without stopping capture.
     */
    fun getBufferedLogs(): List<LogEntry> {
        return logBuffer.toList()
    }

    /**
     * Parse a logcat line into a LogEntry.
     * Format: MM-DD HH:MM:SS.mmm  PID  TID LEVEL TAG     : MESSAGE
     * Example: 11-24 10:35:09.552  662  4254 I ArtService: Dexopt result...
     */
    private fun parseLogLine(line: String): LogEntry? {
        try {
            // Skip empty lines and logcat system messages
            if (line.isBlank() || line.startsWith("-----")) {
                return null
            }

            // Split by spaces (but preserve message)
            val parts = line.trim().split(Regex("\\s+"), limit = 6)
            if (parts.size < 6) {
                return null
            }

            val dateTime = "${parts[0]} ${parts[1]}"  // MM-DD HH:MM:SS.mmm
            val pid = parts[2].toIntOrNull() ?: return null
            val tid = parts[3].toIntOrNull() ?: return null
            val levelChar = parts[4]
            val tagAndMessage = parts[5]

            // Split tag and message
            val colonIndex = tagAndMessage.indexOf(':')
            if (colonIndex < 0) return null

            val tag = tagAndMessage.substring(0, colonIndex).trim()
            val message = tagAndMessage.substring(colonIndex + 1).trim()

            // Parse log level
            val level = when (levelChar.firstOrNull()) {
                'V' -> LogLevel.VERBOSE
                'D' -> LogLevel.DEBUG
                'I' -> LogLevel.INFO
                'W' -> LogLevel.WARN
                'E' -> LogLevel.ERROR
                'A' -> LogLevel.ASSERT
                else -> return null
            }

            return LogEntry(
                timestamp = dateTime,
                pid = pid,
                tid = tid,
                level = level,
                tag = tag,
                message = message
            )
        } catch (e: Exception) {
            // Silently skip unparseable lines
            return null
        }
    }
}
