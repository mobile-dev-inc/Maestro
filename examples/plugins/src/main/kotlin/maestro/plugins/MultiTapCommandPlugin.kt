package maestro.plugins

import kotlinx.coroutines.delay
import maestro.orchestra.Orchestra
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.ElementSelector
import maestro.Point
import maestro.plugins.CommandPlugin
import maestro.plugins.PluginExecutionContext
import maestro.js.JsEngine
import maestro.TapRepeat
import com.fasterxml.jackson.core.JsonLocation

/**
 * Command data for multi-tap functionality with delays
 */
data class MultiTapCommandData(
    val point: String? = null,
    val x: Int? = null,
    val y: Int? = null,
    val selector: Map<String, Any>? = null,
    val count: Int = 2,
    val delayMs: Long = 500,
    val longPress: Boolean = false,
    val retryIfNoChange: Boolean = false
) {
    
    fun hasPoint(): Boolean = point != null || (x != null && y != null)
    fun hasSelector(): Boolean = selector != null
    
    fun getCoordinates(): Pair<Int, Int>? {
        return when {
            x != null && y != null -> Pair(x, y)
            point != null -> {
                val coords = point.split(",").map { it.trim().toInt() }
                if (coords.size == 2) Pair(coords[0], coords[1]) else null
            }
            else -> null
        }
    }
}

/**
 * Plugin for performing multiple taps with configurable delays between taps.
 * This plugin demonstrates how to reuse built-in Maestro commands from within a plugin.
 * 
 * Examples:
 * - multiTap: 
 *     point: "100,200"
 *     count: 3
 *     delayMs: 1000
 * 
 * - multiTap:
 *     x: 100
 *     y: 200
 *     count: 5
 *     delayMs: 500
 *     longPress: true
 *
 * - multiTap:
 *     selector:
 *       text: "Submit"
 *     count: 2
 *     delayMs: 750
 */
class MultiTapCommandPlugin : CommandPlugin<MultiTapCommandData> {
    
    override val commandName: String = "multiTap"
    override val commandClass: Class<MultiTapCommandData> = MultiTapCommandData::class.java
    
    override fun parseCommand(yamlContent: Any?, location: JsonLocation): MultiTapCommandData {
        return when (yamlContent) {
            is Map<*, *> -> {
                val point = yamlContent["point"] as? String
                val x = (yamlContent["x"] as? Number)?.toInt()
                val y = (yamlContent["y"] as? Number)?.toInt()
                val selector = yamlContent["selector"] as? Map<String, Any>
                val count = (yamlContent["count"] as? Number)?.toInt() ?: 2
                val delayMs = (yamlContent["delayMs"] as? Number)?.toLong() ?: 500L
                val longPress = yamlContent["longPress"] as? Boolean ?: false
                val retryIfNoChange = yamlContent["retryIfNoChange"] as? Boolean ?: false
                
                MultiTapCommandData(
                    point = point,
                    x = x,
                    y = y,
                    selector = selector,
                    count = count,
                    delayMs = delayMs,
                    longPress = longPress,
                    retryIfNoChange = retryIfNoChange
                )
            }
            else -> throw IllegalArgumentException("MultiTap command requires a configuration object")
        }
    }
    
    override fun evaluateScripts(commandData: MultiTapCommandData, jsEngine: JsEngine): MultiTapCommandData {
        // Evaluate point if it contains variables
        val evaluatedPoint = commandData.point?.let { point ->
            if (point.contains("$")) {
                jsEngine.evaluateScript("`$point`", emptyMap(), "multiTap-point", false) as? String ?: point
            } else point
        }
        
        return commandData.copy(point = evaluatedPoint)
    }
    
    override suspend fun executeCommand(commandData: MultiTapCommandData, context: PluginExecutionContext): Boolean {
        validateCommand(commandData)
        
        when {
            commandData.hasPoint() -> {
                executePointTaps(commandData, context)
            }
            commandData.hasSelector() -> {
                executeSelectorTaps(commandData, context)
            }
            else -> throw IllegalArgumentException("MultiTap requires either point coordinates or element selector")
        }
        
        return true // MultiTap mutates the UI
    }
    
    private suspend fun executePointTaps(commandData: MultiTapCommandData, context: PluginExecutionContext) {
        val coordinates = commandData.getCoordinates()
            ?: throw IllegalArgumentException("Invalid point coordinates")
        
        repeat(commandData.count) { tapIndex ->
            if (tapIndex > 0) {
                delay(commandData.delayMs)
            }
            
            // Use the built-in Maestro tap functionality
            context.maestro.tap(
                x = coordinates.first,
                y = coordinates.second,
                retryIfNoChange = commandData.retryIfNoChange,
                longPress = commandData.longPress,
                tapRepeat = null, // We handle the repetition ourselves with delays
                waitToSettleTimeoutMs = null
            )
        }
    }
    
    private suspend fun executeSelectorTaps(commandData: MultiTapCommandData, context: PluginExecutionContext) {
        val selector = commandData.selector ?: return
        
        repeat(commandData.count) { tapIndex ->
            if (tapIndex > 0) {
                delay(commandData.delayMs)
            }
            
            // Use Maestro's element tap functionality by creating built-in commands
            // Create an ElementSelector from the selector map
            val elementSelector = createElementSelector(selector)
            
            try {
                // Create and execute a TapOnElementCommand using built-in Maestro infrastructure
                val tapCommand = TapOnElementCommand(
                    selector = elementSelector,
                    retryIfNoChange = commandData.retryIfNoChange,
                    waitUntilVisible = true,
                    longPress = commandData.longPress,
                    repeat = null, // We handle repetition ourselves
                    waitToSettleTimeoutMs = null
                )
                
                // Use Orchestra to execute the command properly
                val orchestra = Orchestra(context.maestro)
                orchestra.executeCommands(
                    listOf(MaestroCommand(command = tapCommand))
                )
            } catch (e: Exception) {
                throw IllegalStateException("Failed to tap on element (attempt ${tapIndex + 1}/${commandData.count}): ${e.message}", e)
            }
        }
    }
    
    private fun createElementSelector(selectorMap: Map<String, Any>): ElementSelector {
        return ElementSelector(
            textRegex = selectorMap["text"] as? String ?: selectorMap["textRegex"] as? String,
            idRegex = selectorMap["id"] as? String ?: selectorMap["idRegex"] as? String,
            enabled = selectorMap["enabled"] as? Boolean,
            checked = selectorMap["checked"] as? Boolean,
            focused = selectorMap["focused"] as? Boolean,
            selected = selectorMap["selected"] as? Boolean,
            optional = selectorMap["optional"] as? Boolean ?: false,
            index = (selectorMap["index"] as? Number)?.toString()
        )
    }
    
    override fun getDescription(commandData: MultiTapCommandData): String {
        val target = when {
            commandData.hasPoint() -> {
                val coords = commandData.getCoordinates()
                "point (${coords?.first},${coords?.second})"
            }
            commandData.hasSelector() -> {
                val selector = commandData.selector
                selector?.get("text")?.let { "\"$it\"" } 
                    ?: selector?.get("id")?.let { "id=$it" } 
                    ?: "element"
            }
            else -> "unknown target"
        }
        
        val pressType = if (commandData.longPress) "Long press" else "Tap"
        return "$pressType $target ${commandData.count} times (${commandData.delayMs}ms delay)"
    }
    
    override fun validateCommand(commandData: MultiTapCommandData) {
        if (commandData.count < 1) {
            throw IllegalArgumentException("Tap count must be at least 1")
        }
        if (commandData.count > 20) {
            throw IllegalArgumentException("Tap count cannot exceed 20")
        }
        if (commandData.delayMs < 0) {
            throw IllegalArgumentException("Delay must be non-negative")
        }
        if (commandData.delayMs > 30000) {
            throw IllegalArgumentException("Delay cannot exceed 30 seconds")
        }
        if (!commandData.hasPoint() && !commandData.hasSelector()) {
            throw IllegalArgumentException("MultiTap requires either point coordinates or element selector")
        }
    }
}
