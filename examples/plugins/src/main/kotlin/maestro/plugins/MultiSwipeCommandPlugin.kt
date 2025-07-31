package maestro.plugins

import kotlinx.coroutines.delay
import maestro.orchestra.Orchestra
import maestro.orchestra.MaestroCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.ElementSelector
import maestro.SwipeDirection
import maestro.Point
import maestro.plugins.CommandPlugin
import maestro.plugins.PluginExecutionContext
import maestro.js.JsEngine
import com.fasterxml.jackson.core.JsonLocation

/**
 * Command data for multi-swipe functionality with delays
 */
data class MultiSwipeCommandData(
    val direction: String,
    val count: Int = 1,
    val delayMs: Long = 1000,
    val duration: Long = 400,
    val startPoint: String? = null,
    val endPoint: String? = null,
    val elementSelector: Map<String, Any>? = null
) {
    
    fun getSwipeDirection(): SwipeDirection {
        return when (direction.lowercase()) {
            "up" -> SwipeDirection.UP
            "down" -> SwipeDirection.DOWN
            "left" -> SwipeDirection.LEFT
            "right" -> SwipeDirection.RIGHT
            else -> throw IllegalArgumentException("Invalid swipe direction: $direction. Must be one of: up, down, left, right")
        }
    }
    
    fun hasPoints(): Boolean = startPoint != null && endPoint != null
    fun hasSelector(): Boolean = elementSelector != null
    
    fun getStartCoordinates(): Pair<Int, Int>? {
        return startPoint?.let { point ->
            val coords = point.split(",").map { it.trim().toInt() }
            if (coords.size == 2) Pair(coords[0], coords[1]) else null
        }
    }
    
    fun getEndCoordinates(): Pair<Int, Int>? {
        return endPoint?.let { point ->
            val coords = point.split(",").map { it.trim().toInt() }
            if (coords.size == 2) Pair(coords[0], coords[1]) else null
        }
    }
}

/**
 * Plugin for performing multiple swipes with configurable delays between swipes.
 * This plugin demonstrates how to reuse built-in Maestro swipe commands from within a plugin.
 * 
 * Examples:
 * - multiSwipe:
 *     direction: "up"
 *     count: 3
 *     delayMs: 800
 *     duration: 500
 * 
 * - multiSwipe:
 *     direction: "left"
 *     startPoint: "200,400"
 *     endPoint: "400,400"
 *     count: 2
 *     delayMs: 1500
 *
 * - multiSwipe:
 *     direction: "down"
 *     elementSelector:
 *       id: "scrollable_list"
 *     count: 5
 *     delayMs: 600
 */
class MultiSwipeCommandPlugin : CommandPlugin<MultiSwipeCommandData> {
    
    override val commandName: String = "multiSwipe"
    override val commandClass: Class<MultiSwipeCommandData> = MultiSwipeCommandData::class.java
    
    override fun parseCommand(yamlContent: Any?, location: JsonLocation): MultiSwipeCommandData {
        return when (yamlContent) {
            is Map<*, *> -> {
                val direction = yamlContent["direction"] as? String
                    ?: throw IllegalArgumentException("MultiSwipe command requires 'direction' parameter")
                
                val count = (yamlContent["count"] as? Number)?.toInt() ?: 1
                val delayMs = (yamlContent["delayMs"] as? Number)?.toLong() ?: 1000L
                val duration = (yamlContent["duration"] as? Number)?.toLong() ?: 400L
                val startPoint = yamlContent["startPoint"] as? String
                val endPoint = yamlContent["endPoint"] as? String
                val elementSelector = yamlContent["elementSelector"] as? Map<String, Any>
                
                MultiSwipeCommandData(
                    direction = direction,
                    count = count,
                    delayMs = delayMs,
                    duration = duration,
                    startPoint = startPoint,
                    endPoint = endPoint,
                    elementSelector = elementSelector
                )
            }
            else -> throw IllegalArgumentException("MultiSwipe command requires a configuration object")
        }
    }
    
    override fun evaluateScripts(commandData: MultiSwipeCommandData, jsEngine: JsEngine): MultiSwipeCommandData {
        // Evaluate points if they contain variables
        val evaluatedStartPoint = commandData.startPoint?.let { point ->
            if (point.contains("$")) {
                jsEngine.evaluateScript("`$point`", emptyMap(), "multiSwipe-startPoint", false) as? String ?: point
            } else point
        }
        
        val evaluatedEndPoint = commandData.endPoint?.let { point ->
            if (point.contains("$")) {
                jsEngine.evaluateScript("`$point`", emptyMap(), "multiSwipe-endPoint", false) as? String ?: point
            } else point
        }
        
        return commandData.copy(
            startPoint = evaluatedStartPoint,
            endPoint = evaluatedEndPoint
        )
    }
    
    override suspend fun executeCommand(commandData: MultiSwipeCommandData, context: PluginExecutionContext): Boolean {
        validateCommand(commandData)
        
        when {
            commandData.hasPoints() -> {
                executePointSwipes(commandData, context)
            }
            commandData.hasSelector() -> {
                executeSelectorSwipes(commandData, context)
            }
            else -> {
                executeDirectionalSwipes(commandData, context)
            }
        }
        
        return true // MultiSwipe mutates the UI
    }
    
    private suspend fun executePointSwipes(commandData: MultiSwipeCommandData, context: PluginExecutionContext) {
        val startCoords = commandData.getStartCoordinates()
            ?: throw IllegalArgumentException("Invalid start point coordinates")
        val endCoords = commandData.getEndCoordinates()
            ?: throw IllegalArgumentException("Invalid end point coordinates")
        
        repeat(commandData.count) { swipeIndex ->
            if (swipeIndex > 0) {
                delay(commandData.delayMs)
            }
            
            try {
                // Create and execute a SwipeCommand using built-in Maestro infrastructure
                val swipeCommand = SwipeCommand(
                    startPoint = Point(startCoords.first, startCoords.second),
                    endPoint = Point(endCoords.first, endCoords.second),
                    duration = commandData.duration,
                    waitToSettleTimeoutMs = null
                )
                
                // Use Orchestra to execute the command properly
                val orchestra = Orchestra(context.maestro)
                orchestra.executeCommands(
                    listOf(MaestroCommand(command = swipeCommand))
                )
            } catch (e: Exception) {
                throw IllegalStateException("Failed to swipe from (${startCoords.first},${startCoords.second}) to (${endCoords.first},${endCoords.second}) (attempt ${swipeIndex + 1}/${commandData.count}): ${e.message}", e)
            }
        }
    }
    
    private suspend fun executeSelectorSwipes(commandData: MultiSwipeCommandData, context: PluginExecutionContext) {
        val selector = commandData.elementSelector ?: return
        
        repeat(commandData.count) { swipeIndex ->
            if (swipeIndex > 0) {
                delay(commandData.delayMs)
            }
            
            try {
                // Create an ElementSelector from the selector map
                val elementSelector = createElementSelector(selector)
                
                // Create and execute a SwipeCommand using built-in Maestro infrastructure
                val swipeCommand = SwipeCommand(
                    direction = commandData.getSwipeDirection(),
                    elementSelector = elementSelector,
                    duration = commandData.duration,
                    waitToSettleTimeoutMs = null
                )
                
                // Use Orchestra to execute the command properly
                val orchestra = Orchestra(context.maestro)
                orchestra.executeCommands(
                    listOf(MaestroCommand(command = swipeCommand))
                )
            } catch (e: Exception) {
                throw IllegalStateException("Failed to swipe ${commandData.direction} on element (attempt ${swipeIndex + 1}/${commandData.count}): ${e.message}", e)
            }
        }
    }
    
    private suspend fun executeDirectionalSwipes(commandData: MultiSwipeCommandData, context: PluginExecutionContext) {
        repeat(commandData.count) { swipeIndex ->
            if (swipeIndex > 0) {
                delay(commandData.delayMs)
            }
            
            try {
                // Create and execute a SwipeCommand using built-in Maestro infrastructure
                val swipeCommand = SwipeCommand(
                    direction = commandData.getSwipeDirection(),
                    duration = commandData.duration,
                    waitToSettleTimeoutMs = null
                )
                
                // Use Orchestra to execute the command properly
                val orchestra = Orchestra(context.maestro)
                orchestra.executeCommands(
                    listOf(MaestroCommand(command = swipeCommand))
                )
            } catch (e: Exception) {
                throw IllegalStateException("Failed to swipe ${commandData.direction} (attempt ${swipeIndex + 1}/${commandData.count}): ${e.message}", e)
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
    
    override fun getDescription(commandData: MultiSwipeCommandData): String {
        val target = when {
            commandData.hasPoints() -> {
                val start = commandData.getStartCoordinates()
                val end = commandData.getEndCoordinates()
                "from (${start?.first},${start?.second}) to (${end?.first},${end?.second})"
            }
            commandData.hasSelector() -> {
                val selector = commandData.elementSelector
                selector?.get("text")?.let { "on \"$it\"" } 
                    ?: selector?.get("id")?.let { "on id=$it" } 
                    ?: "on element"
            }
            else -> "on screen"
        }
        
        return "Swipe ${commandData.direction} $target ${commandData.count} times (${commandData.delayMs}ms delay, ${commandData.duration}ms duration)"
    }
    
    override fun validateCommand(commandData: MultiSwipeCommandData) {
        if (commandData.count < 1) {
            throw IllegalArgumentException("Swipe count must be at least 1")
        }
        if (commandData.count > 20) {
            throw IllegalArgumentException("Swipe count cannot exceed 20")
        }
        if (commandData.delayMs < 0) {
            throw IllegalArgumentException("Delay must be non-negative")
        }
        if (commandData.delayMs > 30000) {
            throw IllegalArgumentException("Delay cannot exceed 30 seconds")
        }
        if (commandData.duration < 100) {
            throw IllegalArgumentException("Swipe duration must be at least 100ms")
        }
        if (commandData.duration > 10000) {
            throw IllegalArgumentException("Swipe duration cannot exceed 10 seconds")
        }
        
        // Validate direction
        try {
            commandData.getSwipeDirection()
        } catch (e: IllegalArgumentException) {
            throw e
        }
        
        // Validate point coordinates if provided
        if (commandData.hasPoints()) {
            commandData.getStartCoordinates() ?: throw IllegalArgumentException("Invalid startPoint format")
            commandData.getEndCoordinates() ?: throw IllegalArgumentException("Invalid endPoint format")
        }
    }
}
