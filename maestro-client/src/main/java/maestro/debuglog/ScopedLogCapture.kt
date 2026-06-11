package maestro.debuglog

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.FileAppender
import org.apache.logging.log4j.core.config.Configuration
import org.apache.logging.log4j.core.config.LoggerConfig
import org.apache.logging.log4j.core.filter.AbstractFilter
import org.apache.logging.log4j.core.layout.PatternLayout
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-flow Log4j2 [FileAppender] capturing only `maestro.*` / `MAESTRO` output to
 * one file. Unlike [LogConfig.configure] it doesn't reconfigure the global context
 * — it attaches an appender for one flow and detaches on [close] (idempotent;
 * [start] returns a no-op instance if attach fails). Lets many flows in one process
 * (Worker, Studio) get per-flow logs without disturbing the host's logging.
 *
 * Concurrency: rather than save/restore the root level per capture (which raced
 * when captures overlapped), the dedicated `maestro`/`MAESTRO` levels are lowered
 * to [Level.ALL] once per JVM and never restored — monotonic, so overlapping
 * captures can't corrupt it, and every other logger is left untouched.
 */
class ScopedLogCapture private constructor(
    private val appenderName: String?,
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
        private val maestroLevelLowered = AtomicBoolean(false)

        /** Attaches a maestro-only appender writing to [logFile]; no-op instance on failure. */
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

                // Lower the maestro hierarchy once per JVM so its events pass the
                // level gate regardless of the host's root level (see class KDoc).
                if (maestroLevelLowered.compareAndSet(false, true)) {
                    ensureLoggerLevel(config, "maestro", Level.ALL)
                    ensureLoggerLevel(config, "MAESTRO", Level.ALL)
                }
                ctx.updateLoggers()

                ScopedLogCapture(appenderName = name)
            } catch (e: Exception) {
                logger.warn("Failed to attach scoped log appender for $logFile — per-flow log will be missing", e)
                ScopedLogCapture(appenderName = null)
            }
        }

        /**
         * Sets [loggerName] to [level], adding a dedicated [LoggerConfig] if the
         * deepest match is a parent. Additive so events still reach the host's
         * root appenders.
         */
        private fun ensureLoggerLevel(config: Configuration, loggerName: String, level: Level) {
            val existing = config.getLoggerConfig(loggerName)
            if (existing.name == loggerName) {
                existing.level = level
            } else {
                config.addLogger(loggerName, LoggerConfig(loggerName, level, true))
            }
        }
    }

    /** Accepts only `maestro.*` / `MAESTRO` events; denies everything else. */
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
