package maestro.plugins

import maestro.plugins.CommandPlugin
import maestro.plugins.PluginExecutionContext
import maestro.js.JsEngine
import com.fasterxml.jackson.core.JsonLocation
import org.slf4j.LoggerFactory

/**
 * Example plugin command data for logging messages.
 */
data class LogCommandData(
    val message: String,
    val level: String = "info"
)

/**
 * Plugin implementation for the log command.
 */
class LogCommandPlugin : CommandPlugin<LogCommandData> {
    
    private val logger = LoggerFactory.getLogger(LogCommandPlugin::class.java)
    
    override val commandName: String = "log"
    override val commandClass: Class<LogCommandData> = LogCommandData::class.java
    
    override fun parseCommand(yamlContent: Any?, location: JsonLocation): LogCommandData {
        return when (yamlContent) {
            is String -> LogCommandData(message = yamlContent)
            is Map<*, *> -> {
                val message = yamlContent["message"] as? String 
                    ?: throw IllegalArgumentException("Log command requires 'message' field")
                val level = yamlContent["level"] as? String ?: "info"
                LogCommandData(message, level)
            }
            else -> throw IllegalArgumentException("Log command must be a string or object with 'message' field")
        }
    }
    
    override fun evaluateScripts(commandData: LogCommandData, jsEngine: JsEngine): LogCommandData {
        return commandData.copy(
            message = jsEngine.evaluateScript(commandData.message, emptyMap(), "log-message", false) as? String 
                ?: commandData.message
        )
    }
    
    override suspend fun executeCommand(commandData: LogCommandData, context: PluginExecutionContext): Boolean {
        // Log using SLF4J for debugging/file logging
        when (commandData.level.lowercase()) {
            "error" -> logger.error(commandData.message)
            "warn", "warning" -> logger.warn(commandData.message)
            "debug" -> logger.debug(commandData.message)
            else -> logger.info(commandData.message)
        }
        
        // Logging doesn't cause UI mutations
        return false
    }
    
    override fun getDescription(commandData: LogCommandData): String {
        val message = if (commandData.message.length > 50) {
            "${commandData.message.take(47)}..."
        } else {
            commandData.message
        }
        return "Log \"$message\""
    }

    override fun validateCommand(commandData: LogCommandData) {
        // No validation needed for log commands
    }
}
