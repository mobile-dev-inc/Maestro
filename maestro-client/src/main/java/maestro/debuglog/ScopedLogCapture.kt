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
 *
 * Concurrency: previous revisions saved + restored the root logger level
 * around the lifetime of each capture, which raced when captures overlapped
 * (every sharded run, every long-lived Worker / Studio JVM). The fix lowers
 * the dedicated `maestro` and `MAESTRO` [LoggerConfig] levels to [Level.ALL]
 * **once per JVM** instead. The mutation is monotonic — never restored —
 * so concurrent captures cannot corrupt the root level. The host's root
 * logger and every other logger (third-party libraries, host code) are
 * left untouched.
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

                // Ensure events from `maestro.*` and `MAESTRO` pass the logger-level
                // gate regardless of the host's root level. Mutates the dedicated
                // maestro hierarchy only, once per JVM — never restored. See class
                // KDoc for the concurrency rationale.
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
         * Ensures [config] has a [LoggerConfig] for [loggerName] at [level].
         * Adds one if the deepest matching config is a parent (e.g. root) —
         * otherwise just updates the level of the existing dedicated config.
         * Additive=true so events still propagate to the host's root
         * appenders (preserves the host's existing console / metrics
         * pipelines).
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
