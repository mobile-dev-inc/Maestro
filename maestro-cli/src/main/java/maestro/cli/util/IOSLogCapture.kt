package maestro.cli.util

import maestro.LogEntry
import maestro.LogLevel
import org.slf4j.LoggerFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * iOS log capture utility using xcrun simctl or idevicesyslog.
 * Captures system logs from iOS Simulator or physical device.
 */
class IOSLogCapture(
    private val deviceId: String? = null,
    private val bufferSize: Int = 5000,
    private val isSimulator: Boolean = true
) {
    private val logger = LoggerFactory.getLogger(IOSLogCapture::class.java)
    private val logBuffer = ConcurrentLinkedQueue<LogEntry>()
    private val isCapturing = AtomicBoolean(false)
    private var captureProcess: Process? = null
    private var captureThread: Thread? = null

    /**
     * Start capturing logs from iOS device or simulator.
     */
    fun start() {
        if (isCapturing.getAndSet(true)) {
            logger.warn("iOS log capture already started")
            return
        }

        logger.info("Starting iOS log capture (device: ${deviceId ?: "default"}, simulator: $isSimulator, bufferSize: $bufferSize)")
        logBuffer.clear()

        try {
            // Build iOS log command
            val command = if (isSimulator) {
                // For simulator: xcrun simctl spawn booted log stream --level debug
                buildList {
                    add("xcrun")
                    add("simctl")
                    add("spawn")
                    add(deviceId ?: "booted")
                    add("log")
                    add("stream")
                    add("--level")
                    add("debug")
                    add("--style")
                    add("compact")
                }
            } else {
                // For physical device: idevicesyslog (if available)
                buildList {
                    add("idevicesyslog")
                    deviceId?.let {
                        add("-u")
                        add(it)
                    }
                }
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

                            parseIOSLogLine(line)?.let { entry ->
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
                        logger.error("Error reading iOS logs", e)
                    }
                }
            }.apply {
                name = "IOSLogCapture-Thread"
                isDaemon = true
                start()
            }

            logger.info("iOS log capture started successfully")
        } catch (e: Exception) {
            logger.error("Failed to start iOS log capture", e)
            isCapturing.set(false)
            throw e
        }
    }

    /**
     * Stop capturing logs and return all captured entries.
     */
    fun stop(): List<LogEntry> {
        if (!isCapturing.getAndSet(false)) {
            logger.warn("iOS log capture not active")
            return emptyList()
        }

        logger.info("Stopping iOS log capture")

        try {
            // Stop the streaming process
            captureProcess?.destroy()
            captureThread?.join(1000)

            // For simulator, also dump recent logs
            if (isSimulator) {
                val dumpCommand = buildList {
                    add("xcrun")
                    add("simctl")
                    add("spawn")
                    add(deviceId ?: "booted")
                    add("log")
                    add("show")
                    add("--last")
                    add("30s")  // Last 30 seconds
                    add("--style")
                    add("compact")
                }

                val dumpProcess = ProcessBuilder(dumpCommand)
                    .redirectErrorStream(true)
                    .start()

                val dumpedLogs = mutableListOf<LogEntry>()
                BufferedReader(InputStreamReader(dumpProcess.inputStream)).use { reader ->
                    reader.lineSequence().forEach { line ->
                        parseIOSLogLine(line)?.let { entry ->
                            dumpedLogs.add(entry)
                        }
                    }
                }

                dumpProcess.waitFor()

                // Combine streamed + dumped logs and deduplicate
                val allLogs = (logBuffer.toList() + dumpedLogs)
                    .distinctBy { "${it.timestamp}:${it.tag}:${it.message}" }
                    .takeLast(bufferSize)

                logger.info("Captured ${allLogs.size} iOS log entries")
                return allLogs
            } else {
                // For physical device, return buffered logs
                val logs = logBuffer.toList()
                logger.info("Captured ${logs.size} iOS log entries")
                return logs
            }
        } catch (e: Exception) {
            logger.error("Error stopping iOS log capture", e)
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
     * Parse an iOS log line into a LogEntry.
     * Compact format: 2024-11-24 11:30:01.234 Df MyProcess[1234] <subsystem>: message
     * Or simpler: 11:30:01.234 I MyProcess: message
     */
    private fun parseIOSLogLine(line: String): LogEntry? {
        try {
            // Skip empty lines and system messages
            if (line.isBlank() || line.startsWith("Filtering") || line.startsWith("Timestamp")) {
                return null
            }

            // Try parsing various iOS log formats
            // Format 1: "2024-11-24 11:30:01.234 Df MyProcess[1234]: message"
            // Format 2: "11:30:01.234 Default MyProcess[1234]: message"
            // Format 3: "11:30:01.234 Error MyProcess: message"

            val parts = line.trim().split(Regex("\\s+"), limit = 5)
            if (parts.size < 4) return null

            val timestamp: String
            val levelStr: String
            val processAndMessage: String

            // Detect format
            if (parts[0].contains("-")) {
                // Has date: "2024-11-24 11:30:01.234"
                timestamp = "${parts[0]} ${parts[1]}"
                levelStr = parts.getOrNull(2) ?: return null
                processAndMessage = parts.drop(3).joinToString(" ")
            } else {
                // No date: "11:30:01.234"
                timestamp = parts[0]
                levelStr = parts.getOrNull(1) ?: return null
                processAndMessage = parts.drop(2).joinToString(" ")
            }

            // Parse log level from iOS level string
            val level = when {
                levelStr.startsWith("Df") || levelStr.equals("Default", ignoreCase = true) -> LogLevel.DEBUG
                levelStr.startsWith("Er") || levelStr.equals("Error", ignoreCase = true) -> LogLevel.ERROR
                levelStr.startsWith("Fa") || levelStr.equals("Fault", ignoreCase = true) -> LogLevel.ASSERT
                levelStr.startsWith("In") || levelStr.equals("Info", ignoreCase = true) -> LogLevel.INFO
                levelStr.contains("Debug") -> LogLevel.DEBUG
                else -> LogLevel.INFO
            }

            // Extract process/tag and message
            val colonIndex = processAndMessage.indexOf(":")
            val (tag, message) = if (colonIndex > 0) {
                val tagPart = processAndMessage.substring(0, colonIndex)
                // Remove [pid] if present
                val cleanTag = tagPart.replace(Regex("\\[\\d+\\]"), "").trim()
                cleanTag to processAndMessage.substring(colonIndex + 1).trim()
            } else {
                "iOS" to processAndMessage
            }

            return LogEntry(
                timestamp = timestamp,
                pid = 0,  // iOS doesn't expose PID easily in compact format
                tid = 0,
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
