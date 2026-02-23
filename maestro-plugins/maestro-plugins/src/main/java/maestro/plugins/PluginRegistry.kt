package maestro.plugins

import maestro.orchestra.Command
import maestro.orchestra.PluginCommand
import maestro.plugins.commands.HelloWorldPlugin
import com.fasterxml.jackson.core.JsonLocation
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Registry for managing command plugins.
 * Handles discovery, registration, and retrieval of plugins.
 */
object PluginRegistry {
    
    private val logger = LoggerFactory.getLogger(PluginRegistry::class.java)
    
    private val plugins = ConcurrentHashMap<String, CommandPlugin<*>>()
    private val commandClassToPlugin = ConcurrentHashMap<Class<*>, CommandPlugin<*>>()
    private val initialized = AtomicBoolean(false)

    /**
     * Initialize the plugin registry by discovering and loading all available plugins.
     */
    fun initialize() {
        if (!initialized.compareAndSet(false, true)) {
            return
        }
        
        logger.info("Initializing Maestro Plugin Registry...")
        
        // Register built-in plugins
        registerBuiltInPlugins()
        
        logger.info("Plugin Registry initialized with ${plugins.size} plugins")
    }

    /**
    * Register all built-in plugins statically.
    */
    private fun registerBuiltInPlugins() {
        val builtInPlugins = listOf(
            HelloWorldPlugin(),
            // Add more plugins here as you create them
        )
        
        for (plugin in builtInPlugins) {
            try {
                registerPlugin(plugin)
            } catch (e: Exception) {
                logger.error("Failed to register built-in plugin: ${plugin::class.java.name}", e)
            }
        }
    }
    
    /**
     * Register a plugin manually.
     */
    fun registerPlugin(plugin: CommandPlugin<*>) {
        val commandName = plugin.commandName
        
        if (plugins.containsKey(commandName)) {
            throw PluginRegistrationException(
                "Command '$commandName' is already registered by another plugin"
            )
        }
        
        // Validate plugin
        validatePlugin(plugin)
        
        plugins[commandName] = plugin
        commandClassToPlugin[plugin.commandClass] = plugin
        
        logger.debug("Registered plugin for command: $commandName")
    }
    
    /**
     * Get a plugin by command name.
     */
    fun getPlugin(commandName: String): CommandPlugin<*>? {
        ensureInitialized()
        return plugins[commandName]
    }
    
    /**
     * Get a plugin by command class.
     */
    fun getPlugin(commandClass: Class<out Any>): CommandPlugin<*>? {
        ensureInitialized()
        return commandClassToPlugin[commandClass]
    }
    
    /**
     * Check if a command is registered as a plugin.
     */
    fun isPluginCommand(commandName: String): Boolean {
        ensureInitialized()
        return plugins.containsKey(commandName)
    }
    
    /**
     * Get all registered command names.
     */
    fun getRegisteredCommands(): Set<String> {
        ensureInitialized()
        return plugins.keys.toSet()
    }
    
    /**
     * Parse a plugin command from YAML content.
     */
    fun parsePluginCommand(commandName: String, yamlContent: Any?, location: JsonLocation): Command? {
        val plugin = getPlugin(commandName) ?: return null
        
        try {
            val commandData = plugin.parseCommand(yamlContent, location)
            // Use unchecked cast to avoid star projection issues
            @Suppress("UNCHECKED_CAST")
            val typedPlugin = (plugin as CommandPlugin<Any>)
            typedPlugin.validateCommand(commandData)
            
            // Get the description from the plugin
            val description = typedPlugin.getDescription(commandData)

            // Extract label and optional parameters from YAML content
            @Suppress("UNCHECKED_CAST")
            val yamlContent = yamlContent as? Map<String, Any> ?: emptyMap<String, Any>()
            @Suppress("UNCHECKED_CAST")
            val label = yamlContent["label"] as? String
            @Suppress("UNCHECKED_CAST")
            val optional = yamlContent["optional"] as? Boolean ?: false
            
            return PluginCommand(
                pluginName = commandName,
                commandData = commandData,
                pluginDescription = description,
                label = label, // Could be extracted from commandData if needed
                optional = optional // Could be extracted from commandData if needed
            )
        } catch (e: Exception) {
            throw PluginValidationException(
                "Failed to parse plugin command '$commandName': ${e.message}",
                e
            )
        }
    }
    
    /**
     * Execute a plugin command.
     */
    suspend fun executePluginCommand(command: Command, context: PluginExecutionContext): Boolean {
        if (command !is PluginCommand) {
            throw PluginExecutionException("Command is not a plugin command: ${command::class.java.name}")
        }
        
        val plugin = getPlugin(command.pluginName)
            ?: throw PluginExecutionException("No plugin found for command: ${command.pluginName}")
        
        try {
            @Suppress("UNCHECKED_CAST")
            return (plugin as CommandPlugin<Any>).executeCommand(command.commandData, context)
        } catch (e: Exception) {
            throw PluginExecutionException(
                "Failed to execute plugin command '${command.pluginName}': ${e.message}",
                e
            )
        }
    }
    
    private fun ensureInitialized() {
        if (!initialized.get()) {
            initialize()
        }
    }
    
    private fun validatePlugin(plugin: CommandPlugin<*>) {
        if (plugin.commandName.isBlank()) {
            throw PluginRegistrationException("Plugin command name cannot be blank")
        }
        
        if (plugin.commandName.contains(" ")) {
            throw PluginRegistrationException(
                "Plugin command name '${plugin.commandName}' cannot contain spaces"
            )
        }
    }
    
    /**
     * Clear all registered plugins (mainly for testing).
     */
    internal fun clear() {
        plugins.clear()
        commandClassToPlugin.clear()
        
        initialized.set(false)
    }
}
