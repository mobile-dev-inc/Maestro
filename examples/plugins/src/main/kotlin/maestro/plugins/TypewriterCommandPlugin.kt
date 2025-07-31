package maestro.plugins

import kotlinx.coroutines.delay
import maestro.orchestra.Orchestra
import maestro.orchestra.MaestroCommand
import maestro.orchestra.InputTextCommand
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.ElementSelector
import maestro.plugins.CommandPlugin
import maestro.plugins.PluginExecutionContext
import maestro.js.JsEngine
import com.fasterxml.jackson.core.JsonLocation

/**
 * Command data for typing text with delays and effects
 */
data class TypewriterCommandData(
    val text: String,
    val elementSelector: Map<String, Any>? = null,
    val delayPerCharacterMs: Long = 100,
    val delayAfterWordMs: Long = 300,
    val delayAfterSentenceMs: Long = 800,
    val clearBefore: Boolean = true,
    val hideKeyboardAfter: Boolean = true
)

/**
 * Plugin for typing text character by character with realistic delays.
 * This plugin demonstrates how to reuse built-in Maestro input commands from within a plugin.
 * 
 * Examples:
 * - typewriter:
 *     text: "Hello World! This is a test."
 *     delayPerCharacterMs: 80
 *     delayAfterWordMs: 200
 *     delayAfterSentenceMs: 500
 * 
 * - typewriter:
 *     elementSelector:
 *       id: "search_field"
 *     text: "Search query here"
 *     clearBefore: true
 *     hideKeyboardAfter: true
 */
class TypewriterCommandPlugin : CommandPlugin<TypewriterCommandData> {
    
    override val commandName: String = "typewriter"
    override val commandClass: Class<TypewriterCommandData> = TypewriterCommandData::class.java
    
    override fun parseCommand(yamlContent: Any?, location: JsonLocation): TypewriterCommandData {
        return when (yamlContent) {
            is String -> {
                TypewriterCommandData(text = yamlContent)
            }
            is Map<*, *> -> {
                val text = yamlContent["text"] as? String
                    ?: throw IllegalArgumentException("Typewriter command requires 'text' parameter")
                
                val elementSelector = yamlContent["elementSelector"] as? Map<String, Any>
                val delayPerCharacterMs = (yamlContent["delayPerCharacterMs"] as? Number)?.toLong() ?: 100L
                val delayAfterWordMs = (yamlContent["delayAfterWordMs"] as? Number)?.toLong() ?: 300L
                val delayAfterSentenceMs = (yamlContent["delayAfterSentenceMs"] as? Number)?.toLong() ?: 800L
                val clearBefore = yamlContent["clearBefore"] as? Boolean ?: true
                val hideKeyboardAfter = yamlContent["hideKeyboardAfter"] as? Boolean ?: true
                
                TypewriterCommandData(
                    text = text,
                    elementSelector = elementSelector,
                    delayPerCharacterMs = delayPerCharacterMs,
                    delayAfterWordMs = delayAfterWordMs,
                    delayAfterSentenceMs = delayAfterSentenceMs,
                    clearBefore = clearBefore,
                    hideKeyboardAfter = hideKeyboardAfter
                )
            }
            else -> throw IllegalArgumentException("Typewriter command requires text string or configuration object")
        }
    }
    
    override fun evaluateScripts(commandData: TypewriterCommandData, jsEngine: JsEngine): TypewriterCommandData {
        // Evaluate text if it contains variables
        val evaluatedText = if (commandData.text.contains("$")) {
            jsEngine.evaluateScript("`${commandData.text}`", emptyMap(), "typewriter-text", false) as? String ?: commandData.text
        } else {
            commandData.text
        }
        
        return commandData.copy(text = evaluatedText)
    }
    
    override suspend fun executeCommand(commandData: TypewriterCommandData, context: PluginExecutionContext): Boolean {
        validateCommand(commandData)
        
        try {
            // First, tap on the element if selector is provided
            if (commandData.elementSelector != null) {
                tapOnElement(commandData.elementSelector, context)
                delay(200) // Small delay after tapping
            }
            
            // Clear existing text if requested
            if (commandData.clearBefore) {
                clearText(context)
                delay(100)
            }
            
            // Type text character by character with realistic delays
            typeTextWithDelays(commandData, context)
            
            // Hide keyboard if requested
            if (commandData.hideKeyboardAfter) {
                delay(200)
                hideKeyboard(context)
            }
            
        } catch (e: Exception) {
            throw IllegalStateException("Failed to execute typewriter command: ${e.message}", e)
        }
        
        return true // Typewriter mutates the UI
    }
    
    private suspend fun tapOnElement(selectorMap: Map<String, Any>, context: PluginExecutionContext) {
        val elementSelector = createElementSelector(selectorMap)
        
        val tapCommand = TapOnElementCommand(
            selector = elementSelector,
            retryIfNoChange = false,
            waitUntilVisible = true
        )
        
        val orchestra = Orchestra(context.maestro)
        orchestra.executeCommands(
            listOf(MaestroCommand(command = tapCommand))
        )
    }
    
    private suspend fun clearText(context: PluginExecutionContext) {
        // Use Maestro's built-in eraseText command
        val eraseCommand = EraseTextCommand(charactersToErase = null)
        
        val orchestra = Orchestra(context.maestro)
        orchestra.executeCommands(
            listOf(MaestroCommand(command = eraseCommand))
        )
    }
    
    private suspend fun typeTextWithDelays(commandData: TypewriterCommandData, context: PluginExecutionContext) {
        val text = commandData.text
        val words = text.split(" ")
        val sentences = text.split(Regex("[.!?]+"))
        
        for ((wordIndex, word) in words.withIndex()) {
            // Type word character by character
            for ((charIndex, char) in word.withIndex()) {
                // Use Maestro's built-in inputText command for each character
                val inputCommand = InputTextCommand(char.toString())
                
                val orchestra = Orchestra(context.maestro)
                orchestra.executeCommands(
                    listOf(MaestroCommand(command = inputCommand))
                )
                
                if (charIndex < word.length - 1) {
                    delay(commandData.delayPerCharacterMs)
                }
            }
            
            // Add space after word (except for the last word)
            if (wordIndex < words.size - 1) {
                val inputCommand = InputTextCommand(" ")
                
                val orchestra = Orchestra(context.maestro)
                orchestra.executeCommands(
                    listOf(MaestroCommand(command = inputCommand))
                )
                
                // Check if we're at the end of a sentence
                val currentText = words.take(wordIndex + 1).joinToString(" ")
                val isSentenceEnd = sentences.any { sentence ->
                    currentText.contains(sentence.trim(), ignoreCase = true) && 
                    currentText.endsWith(sentence.trim().takeLast(1))
                }
                
                delay(if (isSentenceEnd) commandData.delayAfterSentenceMs else commandData.delayAfterWordMs)
            }
        }
    }
    
    private suspend fun hideKeyboard(context: PluginExecutionContext) {
        // Use Maestro's built-in hideKeyboard command
        val hideKeyboardCommand = HideKeyboardCommand()
        
        val orchestra = Orchestra(context.maestro)
        orchestra.executeCommands(
            listOf(MaestroCommand(command = hideKeyboardCommand))
        )
    }
    
    private fun createElementSelector(selectorMap: Map<String, Any>): maestro.orchestra.ElementSelector {
        return maestro.orchestra.ElementSelector(
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
    
    override fun getDescription(commandData: TypewriterCommandData): String {
        val target = commandData.elementSelector?.let { selector ->
            selector["text"]?.let { " into \"$it\"" } 
                ?: selector["id"]?.let { " into id=$it" } 
                ?: " into element"
        } ?: ""
        
        val textPreview = if (commandData.text.length > 30) {
            "${commandData.text.take(27)}..."
        } else {
            commandData.text
        }
        
        return "Type \"$textPreview\"$target with typewriter effect (${commandData.delayPerCharacterMs}ms per char)"
    }
    
    override fun validateCommand(commandData: TypewriterCommandData) {
        if (commandData.text.isEmpty()) {
            throw IllegalArgumentException("Typewriter text cannot be empty")
        }
        if (commandData.text.length > 1000) {
            throw IllegalArgumentException("Typewriter text cannot exceed 1000 characters")
        }
        if (commandData.delayPerCharacterMs < 0) {
            throw IllegalArgumentException("Character delay must be non-negative")
        }
        if (commandData.delayPerCharacterMs > 2000) {
            throw IllegalArgumentException("Character delay cannot exceed 2 seconds")
        }
        if (commandData.delayAfterWordMs < 0) {
            throw IllegalArgumentException("Word delay must be non-negative")
        }
        if (commandData.delayAfterWordMs > 5000) {
            throw IllegalArgumentException("Word delay cannot exceed 5 seconds")
        }
        if (commandData.delayAfterSentenceMs < 0) {
            throw IllegalArgumentException("Sentence delay must be non-negative")
        }
        if (commandData.delayAfterSentenceMs > 10000) {
            throw IllegalArgumentException("Sentence delay cannot exceed 10 seconds")
        }
    }
}
