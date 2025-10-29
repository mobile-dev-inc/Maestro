package maestro.plugins

import maestro.js.JsEngine
import maestro.orchestra.Command
import maestro.orchestra.PluginCommand
import com.fasterxml.jackson.core.JsonLocation

/**
 * Base interface for all Maestro command plugins.
 * Plugins implement this interface to define new YAML commands.
 */
interface CommandPlugin<T : Any> {
    
    /**
     * The name of the command as it appears in YAML files.
     * For example: "customCommand", "myAction", etc.
     */
    val commandName: String
    
    /**
     * The class type of the command data this plugin handles.
     */
    val commandClass: Class<T>
    
    /**
     * Parse the command from YAML content.
     * This method is called when the parser encounters the command name in a YAML file.
     * 
     * @param yamlContent The raw YAML content for this command
     * @param location The location in the YAML file where this command appears
     * @return The parsed command data instance
     */
    fun parseCommand(yamlContent: Any?, location: JsonLocation): T
    
    /**
     * Execute the command.
     * This method is called by the Orchestra when it's time to execute the command.
     * 
     * @param commandData The command data instance to execute
     * @param context The execution context containing maestro instance and other utilities
     * @return true if the command executed successfully and caused UI mutations, false otherwise
     */
    suspend fun executeCommand(commandData: T, context: PluginExecutionContext): Boolean
    
    /**
     * Get the description of the command for display in the flow output.
     * This method is called to generate the text that appears in the test flow UI.
     * 
     * @param commandData The command data instance
     * @return A human-readable description of what this command does
     */
    fun getDescription(commandData: T): String {
        // Default implementation returns a generic description
        return "$commandName command"
    }
    
    /**
     * Validate the command parameters.
     * This method is called during parsing to ensure the command is valid.
     * 
     * @param commandData The command data to validate
     * @throws PluginValidationException if the command is invalid
     */
    fun validateCommand(commandData: T) {
        // Default implementation does nothing - override if validation is needed
    }
    
    /**
     * Evaluate scripts in the command data.
     * This method is called to process JavaScript expressions in command parameters.
     * 
     * @param commandData The command data to evaluate
     * @param jsEngine The JavaScript engine to use for evaluation
     * @return The command data with scripts evaluated
     */
    fun evaluateScripts(commandData: T, jsEngine: JsEngine): T {
        // Default implementation returns the data unchanged
        return commandData
    }
}
