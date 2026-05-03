package maestro.orchestra.plugin

import org.slf4j.LoggerFactory
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

/**
 * Loads plugins from specific JAR files using Java's ServiceLoader mechanism.
 *
 * Each plugin JAR must contain a META-INF/services/maestro.orchestra.plugin.MaestroPlugin
 * file listing the fully-qualified class names of plugin implementations.
 */
object PluginLoader {

    private val logger = LoggerFactory.getLogger(PluginLoader::class.java)

    /**
     * Loads plugins from specified references (file paths or installed plugin names).
     *
     * @param pluginRefs List of plugin references (paths or names)
     * @return List of loaded plugin instances
     */
    fun loadPlugins(pluginRefs: List<String>): List<MaestroPlugin> {
        if (pluginRefs.isEmpty()) {
            logger.info("No plugins specified")
            return emptyList()
        }

        // Resolve all plugin references to actual JAR files
        val jarFiles = pluginRefs.map { ref ->
            try {
                PluginManager.resolvePlugin(ref)
            } catch (e: IllegalArgumentException) {
                logger.error("Failed to resolve plugin: $ref - ${e.message}")
                throw e
            }
        }

        return loadPluginJars(jarFiles)
    }

    /**
     * Loads plugins from specified JAR files.
     *
     * @param jarFiles List of plugin JAR files to load
     * @return List of loaded plugin instances
     */
    fun loadPluginJars(jarFiles: List<File>): List<MaestroPlugin> {
        if (jarFiles.isEmpty()) {
            logger.info("No plugin JARs specified")
            return emptyList()
        }

        val plugins = mutableListOf<MaestroPlugin>()

        // Validate all files exist and are JARs
        jarFiles.forEach { jarFile ->
            if (!jarFile.exists()) {
                logger.error("Plugin JAR not found: ${jarFile.absolutePath}")
                throw IllegalArgumentException("Plugin JAR not found: ${jarFile.absolutePath}")
            }
            if (jarFile.extension != "jar") {
                logger.error("Not a JAR file: ${jarFile.absolutePath}")
                throw IllegalArgumentException("Plugin file must be a JAR: ${jarFile.absolutePath}")
            }
            logger.info("Loading plugin from: ${jarFile.absolutePath}")
        }

        // Create a class loader with all plugin JARs
        val urls = jarFiles.map { it.toURI().toURL() }.toTypedArray()
        val classLoader = URLClassLoader(urls, this.javaClass.classLoader)

        // Use ServiceLoader to discover plugin implementations
        val serviceLoader = ServiceLoader.load(MaestroPlugin::class.java, classLoader)

        serviceLoader.forEach { plugin ->
            try {
                logger.info("Loaded plugin: ${plugin.name} (id: ${plugin.id})")
                plugins.add(plugin)
            } catch (e: Exception) {
                logger.error("Failed to load plugin: ${e.message}", e)
                throw RuntimeException("Failed to load plugin: ${e.message}", e)
            }
        }

        logger.info("Successfully loaded ${plugins.size} plugin(s)")
        return plugins
    }
}
