package maestro.plugins.commands

import maestro.plugins.CommandPlugin
import maestro.plugins.PluginExecutionContext
import maestro.js.JsEngine
import com.fasterxml.jackson.core.JsonLocation
import kotlinx.coroutines.delay

/**
 * Command data for wait/delay operations.
 * 
 * @property durationMs Duration to wait in milliseconds
 * @property originalExpression Original string expression before script evaluation (if any)
 */
data class WaitCommandData(
    val durationMs: Long,
    val originalExpression: String? = null
)

/**
 * Plugin that pauses test execution for a specified duration.
 * 
 * Supports multiple input formats:
 * - Number: `wait: 2` (seconds)
 * - String expression: `wait: ${DELAY_TIME}` (evaluated via JS engine)
 * - Object: `wait: { seconds: 2.5 }`
 */
class WaitPlugin : CommandPlugin<WaitCommandData> {

    override val commandName = "wait"
    override val commandClass = WaitCommandData::class.java

    override fun parseCommand(yamlContent: Any?, location: JsonLocation): WaitCommandData {
        return when (yamlContent) {
            is Number -> WaitCommandData((yamlContent.toDouble() * 1000).toLong())
            is String -> WaitCommandData(0, originalExpression = yamlContent)
            is Map<*, *> -> {
                val seconds = (yamlContent["seconds"] as? Number)?.toDouble()
                    ?: throw IllegalArgumentException("'seconds' parameter is required")
                WaitCommandData((seconds * 1000).toLong())
            }
            else -> throw IllegalArgumentException("Invalid format. Expected number, string expression, or object with 'seconds'")
        }
    }

    override fun evaluateScripts(commandData: WaitCommandData, jsEngine: JsEngine): WaitCommandData {
        return if (commandData.originalExpression != null) {
            val result = jsEngine.evaluateScript(commandData.originalExpression, emptyMap(), "wait-duration", false)
            val seconds = when (result) {
                is Number -> result.toDouble()
                is String -> result.toDoubleOrNull() ?: 0.0
                else -> 0.0
            }
            commandData.copy(durationMs = (seconds * 1000).toLong(), originalExpression = null)
        } else {
            commandData
        }
    }

    override suspend fun executeCommand(commandData: WaitCommandData, context: PluginExecutionContext): Boolean {
        delay(commandData.durationMs)
        return false
    }

    override fun getDescription(commandData: WaitCommandData): String {
        val seconds = commandData.durationMs / 1000.0
        return "Wait ${seconds}s"
    }

    override fun validateCommand(commandData: WaitCommandData) {
        require(commandData.durationMs >= 0) { "Wait duration must be non-negative" }
        require(commandData.durationMs <= 300_000) { "Wait duration cannot exceed 300 seconds (5 minutes)" }
    }
}