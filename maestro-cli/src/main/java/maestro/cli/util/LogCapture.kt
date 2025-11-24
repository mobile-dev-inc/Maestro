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
            // Stop the streaming process
            captureProcess?.destroy()
            captureThread?.join(1000)

            // Dump recent logs from the device
            val dumpCommand = buildList {
                add("adb")
                deviceId?.let {
                    add("-s")
                    add(it)
                }
                add("logcat")
                add("-d")  // Dump mode - get recent logs
                add("-v")
                add("time")
                add("-t")
                add(bufferSize.toString())  // Get last N entries
            }

            val dumpProcess = ProcessBuilder(dumpCommand)
                .redirectErrorStream(true)
                .start()

            val dumpedLogs = mutableListOf<LogEntry>()
            BufferedReader(InputStreamReader(dumpProcess.inputStream)).use { reader ->
                reader.lineSequence().forEach { line ->
                    parseLogLine(line)?.let { entry ->
                        dumpedLogs.add(entry)
                    }
                }
            }

            dumpProcess.waitFor()
            logger.info("Captured ${dumpedLogs.size} log entries")
            return dumpedLogs
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
     * Format: MM-DD HH:MM:SS.mmm LEVEL/TAG(  PID): MESSAGE
     * Example: 11-24 11:18:08.068 D/InetDiagMessage(  662): Destroyed live tcp sockets
     */
    private fun parseLogLine(line: String): LogEntry? {
        try {
            // Skip empty lines and logcat system messages
            if (line.isBlank() || line.startsWith("-----")) {
                return null
            }

            // Parse: "MM-DD HH:MM:SS.mmm LEVEL/TAG(  PID): MESSAGE"
            val regex = Regex("""(\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3})\s+([VDIWEA])/([^(]+)\(\s*(\d+)\):\s*(.*)""")
            val match = regex.matchEntire(line.trim()) ?: return null

            val (timestamp, levelChar, tag, pidStr, message) = match.destructured

            val level = when (levelChar) {
                "V" -> LogLevel.VERBOSE
                "D" -> LogLevel.DEBUG
                "I" -> LogLevel.INFO
                "W" -> LogLevel.WARN
                "E" -> LogLevel.ERROR
                "A" -> LogLevel.ASSERT
                else -> return null
            }

            return LogEntry(
                timestamp = timestamp,
                pid = pidStr.toInt(),
                tid = 0,  // Not available in this format
                level = level,
                tag = tag.trim(),
                message = message
            )
        } catch (e: Exception) {
            // Silently skip unparseable lines
            return null
        }
    }
}
