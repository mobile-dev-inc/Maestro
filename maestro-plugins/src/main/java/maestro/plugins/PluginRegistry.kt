package maestro.plugins

import maestro.orchestra.Command
import maestro.orchestra.PluginCommand
import com.fasterxml.jackson.core.JsonLocation
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
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
    private val pluginClassLoaders = mutableListOf<URLClassLoader>()
    private var initialized = false
    private var pluginsDirectory: Path? = null
    private var customPluginsDirectory: Path? = null
    
    /**
     * Set a custom plugins directory that will be used during initialization.
     * Must be called before initialize().
     */
    fun setPluginsDirectory(pluginsDir: Path?) {
        if (initialized) {
            logger.warn("Cannot change plugins directory after initialization")
            return
        }
        customPluginsDirectory = pluginsDir
    }

    /**
     * Initialize the plugin registry by discovering and loading all available plugins.
     * @param pluginsDir Optional custom plugins directory. If null, uses previously set directory or ~/.maestro/plugins
     */
    fun initialize(pluginsDir: Path? = null) {
        if (initialized) {
            return
        }
        
        logger.info("Initializing Maestro Plugin Registry...")
        
        // Clear existing plugins
        plugins.clear()
        commandClassToPlugin.clear()
        
        // Close existing plugin class loaders
        pluginClassLoaders.forEach { it.close() }
        pluginClassLoaders.clear()
        
        // Set plugins directory (priority: parameter > custom set > default)
        pluginsDirectory = pluginsDir ?: customPluginsDirectory ?: getDefaultPluginsDirectory()
        
        // Load built-in plugins using ServiceLoader
        val serviceLoader = ServiceLoader.load(CommandPlugin::class.java)
        for (plugin in serviceLoader) {
            try {
                registerPlugin(plugin)
            } catch (e: Exception) {
                logger.error("Failed to register built-in plugin: ${plugin::class.java.name}", e)
            }
        }
        
        // Load external plugins from JAR files
        loadExternalPlugins()
        
        initialized = true
        logger.info("Plugin Registry initialized with ${plugins.size} plugins")
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
        if (!initialized) {
            initialize()
        }
    }
    
    /**
     * Load external plugins from JAR files in the plugins directory.
     */
    private fun loadExternalPlugins() {
        val pluginsDir = pluginsDirectory ?: return
        
        if (!pluginsDir.exists() || !pluginsDir.isDirectory()) {
            logger.debug("Plugins directory does not exist: $pluginsDir")
            return
        }
        
        logger.info("Loading external plugins from: $pluginsDir")
        
        val jarFiles = pluginsDir.listDirectoryEntries("*.jar")
        if (jarFiles.isEmpty()) {
            logger.debug("No plugin JAR files found in: $pluginsDir")
            return
        }
        
        for (jarFile in jarFiles) {
            try {
                loadPluginFromJar(jarFile)
            } catch (e: Exception) {
                logger.error("Failed to load plugin from JAR: $jarFile", e)
            }
        }
    }
    
    /**
     * Load plugins from a specific JAR file.
     */
    private fun loadPluginFromJar(jarPath: Path) {
        logger.debug("Loading plugin from JAR: $jarPath")
        
        val jarUrl = jarPath.toUri().toURL()
        val classLoader = URLClassLoader(arrayOf(jarUrl), this::class.java.classLoader)
        pluginClassLoaders.add(classLoader)
        
        // Use ServiceLoader with the JAR's class loader
        val serviceLoader = ServiceLoader.load(CommandPlugin::class.java, classLoader)
        
        var pluginCount = 0
        for (plugin in serviceLoader) {
            try {
                registerPlugin(plugin)
                pluginCount++
                logger.debug("Loaded plugin '${plugin.commandName}' from JAR: $jarPath")
            } catch (e: Exception) {
                logger.error("Failed to register plugin from JAR $jarPath: ${plugin::class.java.name}", e)
            }
        }
        
        if (pluginCount == 0) {
            logger.warn("No valid plugins found in JAR: $jarPath")
        } else {
            logger.info("Loaded $pluginCount plugin(s) from JAR: $jarPath")
        }
    }
    
    /**
     * Get the default plugins directory (~/.maestro/plugins).
     */
    private fun getDefaultPluginsDirectory(): Path {
        val userHome = System.getProperty("user.home")
        return Path.of(userHome, ".maestro", "plugins")
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
        
        // Close plugin class loaders
        pluginClassLoaders.forEach { it.close() }
        pluginClassLoaders.clear()
        
        initialized = false
    }
}
