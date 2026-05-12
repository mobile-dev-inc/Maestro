package maestro.debuglog

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.FileAppender
import org.apache.logging.log4j.core.filter.AbstractFilter
import org.apache.logging.log4j.core.layout.PatternLayout
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-flow scoped Log4j2 [FileAppender] that captures only `maestro.*` and the
 * dedicated `MAESTRO` logger output to a single file. Unlike [LogConfig.configure],
 * this does NOT reconfigure the global Log4j context — it attaches an additional
 * appender to the root logger for the duration of one flow, then detaches on
 * [close].
 *
 * Use case: many flows in one process (Maestro Worker, embedded Studio server)
 * need per-flow logs without disturbing the host's global logging configuration.
 *
 * Lifecycle:
 *   val capture = ScopedLogCapture.start(File("/tmp/job-xyz/maestro.log"))
 *   try { /* ...run flow... */ } finally { capture.close() }
 *
 * Safe to call [close] more than once (idempotent). If attach fails (e.g.,
 * a log4j classpath mismatch) [start] returns a no-op instance — callers
 * always get a usable Closeable and can rely on close() being safe.
 */
class ScopedLogCapture private constructor(
    private val appenderName: String?,
    private val previousRootLevel: Level?,
) : Closeable {

    @Volatile
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        val name = appenderName ?: return
        try {
            val ctx = LogManager.getContext(false) as LoggerContext
            val config = ctx.configuration
            config.rootLogger.removeAppender(name)
            previousRootLevel?.let { config.rootLogger.level = it }
            config.getAppender<Appender>(name)?.stop()
            ctx.updateLoggers()
        } catch (e: Exception) {
            logger.warn("Failed to detach scoped log appender '$name'", e)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ScopedLogCapture::class.java)
        private const val FILE_LOG_PATTERN = "%d{HH:mm:ss.SSS} [%5level] %logger.%method: %msg%n"
        private val nameCounter = AtomicInteger(0)

        /**
         * Attaches a maestro-only [FileAppender] writing to [logFile] and returns
         * a [ScopedLogCapture] whose [close] detaches it. Returns a no-op
         * instance if attach fails — callers always get a usable Closeable.
         */
        fun start(logFile: File): ScopedLogCapture {
            return try {
                logFile.parentFile?.mkdirs()

                val ctx = LogManager.getContext(false) as LoggerContext
                val config = ctx.configuration
                val name = "ScopedLogCapture-${nameCounter.incrementAndGet()}"

                val layout = PatternLayout.newBuilder()
                    .withPattern(FILE_LOG_PATTERN)
                    .withConfiguration(config)
                    .build()

                val appender = FileAppender.newBuilder()
                    .setName(name)
                    .withFileName(logFile.absolutePath)
                    .setLayout(layout)
                    .setConfiguration(config)
                    .setFilter(MaestroOnlyFilter)
                    .build()
                appender.start()
                config.addAppender(appender)
                config.rootLogger.addAppender(appender, null, null)

                // Log4j2's default config sets the root logger to ERROR. If we
                // leave it there our INFO/DEBUG `maestro.*` events never reach
                // the appender. Lower the level for the lifetime of the capture
                // and restore it on close so we don't permanently affect the
                // host's logging configuration.
                val previousLevel = config.rootLogger.level
                if (previousLevel == null || previousLevel.isMoreSpecificThan(Level.DEBUG)) {
                    config.rootLogger.level = Level.ALL
                }
                ctx.updateLoggers()

                ScopedLogCapture(appenderName = name, previousRootLevel = previousLevel)
            } catch (e: Exception) {
                logger.warn("Failed to attach scoped log appender for $logFile — per-flow log will be missing", e)
                ScopedLogCapture(appenderName = null, previousRootLevel = null)
            }
        }
    }

    /**
     * Accepts only events whose logger name starts with `maestro.` or is the
     * dedicated `MAESTRO` logger. Everything else (third-party libs, host
     * application code) is denied so the per-flow file contains only flow
     * output.
     */
    internal object MaestroOnlyFilter : AbstractFilter() {
        override fun filter(event: LogEvent): Filter.Result {
            val loggerName = event.loggerName ?: return Filter.Result.DENY
            return if (loggerName.startsWith("maestro.") || loggerName == "MAESTRO") {
                Filter.Result.ACCEPT
            } else {
                Filter.Result.DENY
            }
        }
    }
}
