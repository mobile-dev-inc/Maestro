package maestro.plugins

import maestro.plugins.CommandPlugin
import maestro.plugins.PluginExecutionContext
import maestro.js.JsEngine
import com.fasterxml.jackson.core.JsonLocation
import kotlinx.coroutines.delay

/**
 * Example plugin command data for waiting.
 */
data class WaitCommandData(
    val seconds: Double,
    val originalExpression: String? = null // Store original string expression for evaluation
)

/**
 * Plugin implementation for the wait command.
 */
class WaitCommandPlugin : CommandPlugin<WaitCommandData> {
    
    override val commandName: String = "wait"
    override val commandClass: Class<WaitCommandData> = WaitCommandData::class.java
    
    override fun parseCommand(yamlContent: Any?, location: JsonLocation): WaitCommandData {
        return when (yamlContent) {
            is Number -> WaitCommandData(yamlContent.toDouble())
            is String -> {
                // Store the string expression for later evaluation
                WaitCommandData(0.0, originalExpression = yamlContent)
            }
            is Map<*, *> -> {
                val seconds = (yamlContent["seconds"] as? Number)?.toDouble()
                    ?: throw IllegalArgumentException("'seconds' parameter is required for wait command")
                WaitCommandData(seconds)
            }
            else -> throw IllegalArgumentException("Invalid wait command format. Expected number, string, or object with 'seconds' property")
        }
    }
    
    override fun evaluateScripts(commandData: WaitCommandData, jsEngine: JsEngine): WaitCommandData {
        return if (commandData.originalExpression != null) {
            val evaluatedValue = jsEngine.evaluateScript(commandData.originalExpression, emptyMap(), "wait-duration", false)
            val seconds = when (evaluatedValue) {
                is Number -> evaluatedValue.toDouble()
                is String -> evaluatedValue.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
            commandData.copy(seconds = seconds, originalExpression = null)
        } else {
            commandData
        }
    }
    
    override suspend fun executeCommand(commandData: WaitCommandData, context: PluginExecutionContext): Boolean {
        val milliseconds = (commandData.seconds * 1000).toLong()
        delay(milliseconds)
        return false // Wait command doesn't mutate UI
    }
    
    override fun getDescription(commandData: WaitCommandData): String {
        return "Wait ${commandData.seconds}s"
    }
    
    override fun validateCommand(commandData: WaitCommandData) {
        if (commandData.seconds < 0) {
            throw IllegalArgumentException("Wait duration must be non-negative")
        }
        if (commandData.seconds > 300) { // 5 minutes max
            throw IllegalArgumentException("Wait duration cannot exceed 300 seconds")
        }
    }
}
