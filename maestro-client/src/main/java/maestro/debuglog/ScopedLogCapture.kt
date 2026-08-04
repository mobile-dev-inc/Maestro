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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-flow Log4j2 [FileAppender] capturing only `maestro.*` / `MAESTRO` output to
 * one file, attached on [start] and detached on [close] without reconfiguring the
 * global context, so many flows in one process (Worker, Studio) get per-flow logs.
 *
 * The appender lives on the `maestro`/`MAESTRO` loggers, not root: a host that sets
 * `additivity="false"` on them (the Worker's `log4j2.xml` does) would starve a
 * root-attached appender, leaving the file empty. Their level is set to [Level.ALL]
 * — idempotent and never restored, so overlapping captures can't corrupt it.
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
            MAESTRO_LOGGERS.forEach { loggerName ->
                val loggerConfig = config.getLoggerConfig(loggerName)
                if (loggerConfig.name == loggerName) loggerConfig.removeAppender(name)
            }
            config.getAppender<Appender>(name)?.stop()
            ctx.updateLoggers()
        } catch (e: Exception) {
            logger.warn("Failed to detach scoped log appender '$name'", e)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(ScopedLogCapture::class.java)
        private const val FILE_LOG_PATTERN = "%d{HH:mm:ss.SSS} [%5level] %logger.%method: %msg%n"

        private val MAESTRO_LOGGERS = listOf("maestro", "MAESTRO")

        private val nameCounter = AtomicInteger(0)

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

                // On the maestro loggers, not root — see class KDoc.
                MAESTRO_LOGGERS.forEach { loggerName ->
                    val loggerConfig = dedicatedLogger(config, loggerName)
                    loggerConfig.level = Level.ALL
                    loggerConfig.addAppender(appender, null, null)
                }
                ctx.updateLoggers()

                ScopedLogCapture(appenderName = name)
            } catch (e: Exception) {
                logger.warn("Failed to attach scoped log appender for $logFile — per-flow log will be missing", e)
                ScopedLogCapture(appenderName = null)
            }
        }

        /** The dedicated [LoggerConfig] for [loggerName], creating an additive one if only a parent matches. */
        private fun dedicatedLogger(config: Configuration, loggerName: String): LoggerConfig {
            val existing = config.getLoggerConfig(loggerName)
            if (existing.name == loggerName) return existing
            return LoggerConfig(loggerName, existing.level, true).also { config.addLogger(loggerName, it) }
        }
    }

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
