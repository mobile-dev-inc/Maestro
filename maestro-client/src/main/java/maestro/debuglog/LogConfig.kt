package maestro.debuglog

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.ConsoleAppender
import org.apache.logging.log4j.core.appender.FileAppender
import org.apache.logging.log4j.core.config.Configurator
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration

object LogConfig {

    private const val DEFAULT_FILE_LOG_PATTERN = "%d{HH:mm:ss.SSS} [%5level] %logger.%method: %msg%n"
    private const val DEFAULT_CONSOLE_LOG_PATTERN = "%highlight([%5level]) %msg%n"

    private val FILE_LOG_PATTERN: String = System.getenv("MAESTRO_CLI_LOG_PATTERN_FILE") ?: DEFAULT_FILE_LOG_PATTERN
    private val CONSOLE_LOG_PATTERN: String = System.getenv("MAESTRO_CLI_LOG_PATTERN_CONSOLE") ?: DEFAULT_CONSOLE_LOG_PATTERN

    fun configure(logFileName: String? = null, printToConsole: Boolean) {
        val builder = ConfigurationBuilderFactory.newConfigurationBuilder()
        builder.setStatusLevel(org.apache.logging.log4j.Level.ERROR)
        builder.setConfigurationName("MaestroConfig")

        val rootLogger = builder.newRootLogger(org.apache.logging.log4j.Level.ALL)

        if (logFileName != null) {
            val fileAppender = createFileAppender(builder, logFileName)
            rootLogger.add(builder.newAppenderRef(fileAppender.getName()))
        }

        if (printToConsole) {
            val consoleAppender = createConsoleAppender(builder)
            rootLogger.add(builder.newAppenderRef(consoleAppender.getName()))
        }


        builder.add(rootLogger)

        val config = builder.build()

        Configurator.reconfigure(config)
    }

    private fun createConsoleAppender(builder: ConfigurationBuilder<BuiltConfiguration>): org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder {
        val consoleAppender = builder.newAppender("Console", "CONSOLE")

        val consoleLayout = builder.newLayout("PatternLayout")
        consoleLayout.addAttribute("pattern", CONSOLE_LOG_PATTERN)
        consoleAppender.add(consoleLayout)

        builder.add(consoleAppender)

        return consoleAppender
    }

    private fun createFileAppender(builder: ConfigurationBuilder<BuiltConfiguration>, logFileName: String): org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder {
        val fileAppender = builder.newAppender("File", FileAppender.PLUGIN_NAME)
        fileAppender.addAttribute("fileName", logFileName)

        val fileLayout = builder.newLayout("PatternLayout")
        fileLayout.addAttribute("pattern", FILE_LOG_PATTERN)

        fileAppender.add(fileLayout)
        builder.add(fileAppender)

        return fileAppender
    }
}
