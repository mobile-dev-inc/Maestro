package maestro.orchestra

import maestro.js.JsEngine

/**
 * Command that represents a plugin command execution.
 * This is a wrapper command that delegates to registered plugins.
 */
data class PluginCommand(
    val pluginName: String,
    val commandData: Any,
    val pluginDescription: String,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = pluginDescription

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        // Plugin-specific script evaluation should be handled by the plugin itself
        // This is just a wrapper command
        return this
    }
}