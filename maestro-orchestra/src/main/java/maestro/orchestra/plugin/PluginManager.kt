package maestro.orchestra.plugin

import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/**
 * Manages plugin installation, listing, and removal.
 *
 * Plugins are stored in ~/.maestro/plugins/ directory.
 */
object PluginManager {

    private val logger = LoggerFactory.getLogger(PluginManager::class.java)

    /**
     * Get the plugins directory (~/.maestro/plugins/)
     */
    fun getPluginsDirectory(): Path {
        val homeDir = System.getProperty("user.home")
        return Paths.get(homeDir, ".maestro", "plugins")
    }

    /**
     * Ensure the plugins directory exists
     */
    fun ensurePluginsDirectory(): Path {
        val pluginsDir = getPluginsDirectory()
        if (!Files.exists(pluginsDir)) {
            Files.createDirectories(pluginsDir)
            logger.info("Created plugins directory: ${pluginsDir.toAbsolutePath()}")
        }
        return pluginsDir
    }

    /**
     * Install a plugin from a JAR file.
     *
     * @param sourceFile The plugin JAR file to install
     * @return The installed plugin file
     * @throws IllegalArgumentException if source file doesn't exist or isn't a JAR
     */
    fun installPlugin(sourceFile: File): File {
        if (!sourceFile.exists()) {
            throw IllegalArgumentException("Plugin file not found: ${sourceFile.absolutePath}")
        }

        if (sourceFile.extension != "jar") {
            throw IllegalArgumentException("Plugin file must be a JAR: ${sourceFile.absolutePath}")
        }

        val pluginsDir = ensurePluginsDirectory()
        val targetFile = pluginsDir.resolve(sourceFile.name).toFile()

        if (targetFile.exists()) {
            logger.warn("Plugin ${sourceFile.name} already exists, overwriting")
        }

        Files.copy(
            sourceFile.toPath(),
            targetFile.toPath(),
            StandardCopyOption.REPLACE_EXISTING
        )

        logger.info("Installed plugin: ${sourceFile.name}")
        return targetFile
    }

    /**
     * List all installed plugins.
     *
     * @return List of installed plugin files
     */
    fun listPlugins(): List<File> {
        val pluginsDir = getPluginsDirectory()

        if (!Files.exists(pluginsDir)) {
            return emptyList()
        }

        return pluginsDir.toFile()
            .listFiles { file -> file.extension == "jar" }
            ?.toList()
            ?: emptyList()
    }

    /**
     * Uninstall a plugin by name.
     *
     * @param pluginName The plugin name (without .jar extension) or filename
     * @return true if plugin was removed, false if not found
     */
    fun uninstallPlugin(pluginName: String): Boolean {
        val pluginsDir = getPluginsDirectory()

        if (!Files.exists(pluginsDir)) {
            return false
        }

        // Support both "aria-plugin" and "aria-plugin.jar"
        val fileName = if (pluginName.endsWith(".jar")) pluginName else "$pluginName.jar"
        val pluginFile = pluginsDir.resolve(fileName).toFile()

        if (!pluginFile.exists()) {
            logger.warn("Plugin not found: $fileName")
            return false
        }

        val deleted = pluginFile.delete()
        if (deleted) {
            logger.info("Uninstalled plugin: $fileName")
        } else {
            logger.error("Failed to delete plugin: $fileName")
        }

        return deleted
    }

    /**
     * Resolve a plugin reference to a file.
     *
     * Can resolve:
     * - Direct file paths: /path/to/plugin.jar
     * - Installed plugin names: aria-plugin
     *
     * @param pluginRef Plugin reference (path or name)
     * @return Resolved plugin file
     * @throws IllegalArgumentException if plugin cannot be resolved
     */
    fun resolvePlugin(pluginRef: String): File {
        // First try as direct file path
        val directFile = File(pluginRef)
        if (directFile.exists() && directFile.extension == "jar") {
            return directFile
        }

        // Try as installed plugin name
        val pluginsDir = getPluginsDirectory()

        // Support both "aria-plugin" and "aria-plugin.jar"
        val fileName = if (pluginRef.endsWith(".jar")) pluginRef else "$pluginRef.jar"
        val installedFile = pluginsDir.resolve(fileName).toFile()

        if (installedFile.exists()) {
            return installedFile
        }

        throw IllegalArgumentException(
            "Plugin not found: $pluginRef\n" +
            "Searched:\n" +
            "  - Direct path: ${directFile.absolutePath}\n" +
            "  - Installed plugins: ${installedFile.absolutePath}\n" +
            "Use 'maestro list-plugins' to see installed plugins."
        )
    }
}
