package maestro.debuglog

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.status.NopStatusListener
import org.slf4j.LoggerFactory

object LogConfig {
    // See https://logback.qos.ch/manual/layouts.html#method

    private const val DEFAULT_FILE_LOG_PATTERN = "%d{HH:mm:ss.SSS} [%5level] %logger.%method: %msg%n"
    private const val DEFAULT_CONSOLE_LOG_PATTERN = "%highlight([%5level]) %msg%n"

    private val FILE_LOG_PATTERN: String = System.getenv("MAESTRO_CLI_LOG_PATTERN_FILE") ?: DEFAULT_FILE_LOG_PATTERN
    private val CONSOLE_LOG_PATTERN: String = System.getenv("MAESTRO_CLI_LOG_PATTERN_CONSOLE") ?: DEFAULT_CONSOLE_LOG_PATTERN

    fun configure(logFileName: String, printToConsole: Boolean) {
        val loggerContext = LoggerFactory.getILoggerFactory() as LoggerContext
        loggerContext.statusManager.add(NopStatusListener())
        loggerContext.reset()

        createAndAddFileAppender(loggerContext, logFileName)
        if (printToConsole) {
            createAndAddConsoleAppender(loggerContext)
        }

        loggerContext.getLogger("ROOT").level = Level.ALL
    }

    private fun createAndAddConsoleAppender(loggerContext: LoggerContext) {
        val encoder = PatternLayoutEncoder().apply {
            context = loggerContext
            pattern = CONSOLE_LOG_PATTERN
            start()
        }

        val consoleAppender = ch.qos.logback.core.ConsoleAppender<ILoggingEvent>().apply {
            context = loggerContext
            setEncoder(encoder)
            start()
        }

        loggerContext.getLogger("ROOT").addAppender(consoleAppender)
    }

    private fun createAndAddFileAppender(loggerContext: LoggerContext, logFileName: String) {
        val encoder = PatternLayoutEncoder().apply {
            context = loggerContext
            pattern = FILE_LOG_PATTERN
            start()
        }

        val fileAppender = FileAppender<ILoggingEvent>().apply {
            context = loggerContext
            setEncoder(encoder)
            file = logFileName
            start()
        }

        loggerContext.getLogger("ROOT").addAppender(fileAppender)
    }
}
