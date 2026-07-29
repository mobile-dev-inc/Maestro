package maestro.debuglog

import maestro.Driver
import maestro.utils.FileUtils
import net.harawata.appdirs.AppDirsFactory
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Properties
import java.util.logging.ConsoleHandler
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

object DebugLogStore {

    private const val APP_NAME = "maestro"
    private const val APP_AUTHOR = "mobile_dev"
    private const val LOG_DIR_DATE_FORMAT = "yyyy-MM-dd_HHmmss"
    private const val KEEP_LOG_COUNT = 6

    // A run never spans this long, so any working directory older than this is an orphan from a
    // crashed run and is safe to reap without touching a concurrently-running process's live dir.
    private const val ORPHAN_DIR_MAX_AGE_MILLIS = 24L * 60 * 60 * 1000

    private val LOGGER = LoggerFactory.getLogger(DebugLogStore::class.java)
    val logDirectory = File(AppDirsFactory.getInstance().getUserLogDir(APP_NAME, null, APP_AUTHOR))

    private val currentRunLogDirectory: File
    private val consoleHandler: ConsoleHandler
    private val fileHandler: FileHandler

    init {
        val dateFormatter = DateTimeFormatter.ofPattern(LOG_DIR_DATE_FORMAT)
        val date = dateFormatter.format(LocalDateTime.now())
        val pid = java.lang.management.ManagementFactory.getRuntimeMXBean().name.split("@")[0]

        currentRunLogDirectory = File(logDirectory, "${date}_$pid")
        currentRunLogDirectory.mkdirs()
        removeOldLogs(logDirectory)

        consoleHandler = ConsoleHandler()
        consoleHandler.level = Level.WARNING
        consoleHandler.formatter = object : SimpleFormatter() {
            override fun format(record: LogRecord): String {
                val level = if (record.level.intValue() > 900) "Error: " else ""
                return "$level${record.message}\n"
            }
        }

        val maestroLogFile = logFile("maestro")
        fileHandler = FileHandler(maestroLogFile.absolutePath)
        fileHandler.level = Level.ALL
        fileHandler.formatter = object : SimpleFormatter() {
            private val format = "[%1\$tF %1\$tT] [%2$-7s] %3\$s %n"

            @Suppress("DefaultLocale")
            @Synchronized
            override fun format(lr: LogRecord): String {
                return java.lang.String.format(
                    format,
                    Date(lr.millis),
                    lr.level.localizedName,
                    lr.message
                )
            }
        }
    }

    fun copyTo(file: File) {
        val local = logFile("maestro")
        local.copyTo(file)
    }

    fun loggerFor(clazz: Class<*>): Logger {
        val logger = Logger.getLogger(clazz.name)
        logger.useParentHandlers = false
        logger.addHandler(consoleHandler)
        logger.addHandler(fileHandler)
        return logger
    }

    fun logOutputOf(processBuilder: ProcessBuilder) {
        val command = processBuilder.command().first() ?: "unknown"
        val logFile = logFile(command)
        val redirect = ProcessBuilder.Redirect.to(logFile)
        processBuilder
            .redirectOutput(redirect)
            .redirectError(redirect)
    }

    fun finalizeRun() {
        fileHandler.close()
        // Archiving debug logs is best-effort cleanup; it must never crash a run that has already
        // completed (e.g. if the log directory was reaped by a concurrent process). See issue #1522.
        try {
            val output = File(currentRunLogDirectory.parent, "${currentRunLogDirectory.name}.zip")
            FileUtils.zipDir(currentRunLogDirectory.toPath(), output.toPath())
            currentRunLogDirectory.deleteRecursively()
        } catch (e: Exception) {
            LOGGER.warn("Failed to archive debug logs for this run; continuing", e)
        }
    }

    private fun logFile(named: String): File {
        return File(currentRunLogDirectory, "$named.log")
    }

    private fun removeOldLogs(baseDir: File) {
        pruneLogs(
            baseDir = baseDir,
            keepZipCount = KEEP_LOG_COUNT,
            orphanDirCutoffMillis = System.currentTimeMillis() - ORPHAN_DIR_MAX_AGE_MILLIS,
        )
    }

    fun logSystemInfo() {
        val logData = """
            Maestro version: ${appVersion()}
            OS: ${System.getProperty("os.name")}
            OS version: ${System.getProperty("os.version")}
            Architecture: ${System.getProperty("os.arch")}
            """.trimIndent() + "\n"

        logFile("system_info").writeText(logData)
    }

    private fun appVersion(): String {
        try {
            val props = Driver::class.java.classLoader.getResourceAsStream("version.properties").use {
                Properties().apply { load(it) }
            }
            return props["version"].toString()
        } catch (ignore: Exception) {
            // no-action
        }
        return "Undefined"
    }
}

/**
 * Prunes the debug log directory without ever deleting a directory that may still be in use by a
 * concurrently-running Maestro process.
 *
 * - Completed runs are stored as `.zip` archives; only the newest [keepZipCount] are retained.
 * - Working directories are owned and deleted by their own run on completion. Any directory left
 *   behind is an orphan from a crashed run and is reaped only when older than [orphanDirCutoffMillis],
 *   so a live run's freshly-modified directory is never removed.
 */
internal fun pruneLogs(baseDir: File, keepZipCount: Int, orphanDirCutoffMillis: Long) {
    if (!baseDir.isDirectory) return
    val entries = baseDir.listFiles() ?: return

    entries.filter { it.isFile && it.extension == "zip" }
        .sortedByDescending { it.name }
        .drop(keepZipCount)
        .forEach { it.delete() }

    entries.filter { it.isDirectory && it.lastModified() < orphanDirCutoffMillis }
        .forEach { it.deleteRecursively() }
}

fun Logger.warn(message: String, throwable: Throwable? = null) {
    if (throwable != null) {
        log(Level.WARNING, message, throwable)
    } else log(Level.WARNING, message)
}

fun Logger.error(message: String, throwable: Throwable? = null) {
    if (throwable != null) {
        log(Level.SEVERE, message, throwable)
    } else log(Level.SEVERE, message)
}
