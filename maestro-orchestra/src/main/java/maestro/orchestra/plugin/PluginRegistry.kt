package maestro.orchestra.plugin

import maestro.orchestra.MaestroCommand
import org.slf4j.LoggerFactory

/**
 * Registry that manages all loaded plugins and broadcasts lifecycle events to them.
 *
 * This class acts as a hub between Orchestra and individual plugins. When Orchestra
 * executes commands, it calls methods on this registry, which then broadcasts
 * those events to all registered plugins.
 *
 * Plugin errors are isolated - if one plugin throws an exception, it won't crash
 * the test or affect other plugins.
 */
class PluginRegistry {

    private val plugins = mutableListOf<MaestroPlugin>()
    private val logger = LoggerFactory.getLogger(PluginRegistry::class.java)

    /**
     * Register a plugin to receive lifecycle hooks.
     *
     * @param plugin The plugin to register
     */
    fun register(plugin: MaestroPlugin) {
        plugins.add(plugin)
        logger.info("Registered plugin: ${plugin.name} (${plugin.id})")
    }

    /**
     * Get all registered plugins.
     */
    fun getPlugins(): List<MaestroPlugin> = plugins.toList()

    /**
     * Initialize all registered plugins.
     *
     * @param context The context to pass to each plugin's onInit method
     */
    fun initializeAll(context: MaestroContext) {
        plugins.forEach { plugin ->
            safeExecute(plugin, "onInit") {
                plugin.onInit(context)
            }
        }
    }

    /**
     * Notify all plugins that a flow is starting.
     *
     * @param commands The list of commands in this flow
     */
    fun notifyFlowStart(commands: List<MaestroCommand>) {
        plugins.forEach { plugin ->
            safeExecute(plugin, "onFlowStart") {
                plugin.onFlowStart(commands)
            }
        }
    }

    /**
     * Notify all plugins that a command is starting.
     *
     * @param index The index of the command
     * @param command The command that is starting
     */
    fun notifyCommandStart(index: Int, command: MaestroCommand) {
        plugins.forEach { plugin ->
            safeExecute(plugin, "onCommandStart") {
                plugin.onCommandStart(index, command)
            }
        }
    }

    /**
     * Notify all plugins that a command has completed successfully.
     *
     * @param index The index of the command
     * @param command The command that completed
     */
    fun notifyCommandComplete(index: Int, command: MaestroCommand) {
        plugins.forEach { plugin ->
            safeExecute(plugin, "onCommandComplete") {
                plugin.onCommandComplete(index, command)
            }
        }
    }

    /**
     * Notify all plugins that a command has failed.
     *
     * @param index The index of the command
     * @param command The command that failed
     * @param error The exception that was thrown
     */
    fun notifyCommandFailed(index: Int, command: MaestroCommand, error: Throwable) {
        plugins.forEach { plugin ->
            safeExecute(plugin, "onCommandFailed") {
                plugin.onCommandFailed(index, command, error)
            }
        }
    }

    /**
     * Notify all plugins that a command was skipped.
     *
     * @param index The index of the command
     * @param command The command that was skipped
     */
    fun notifyCommandSkipped(index: Int, command: MaestroCommand) {
        plugins.forEach { plugin ->
            safeExecute(plugin, "onCommandSkipped") {
                plugin.onCommandSkipped(index, command)
            }
        }
    }

    /**
     * Notify all plugins that a flow has completed.
     *
     * @param flowName The name of the flow
     * @param success Whether the flow completed successfully
     */
    fun notifyFlowComplete(flowName: String, success: Boolean) {
        plugins.forEach { plugin ->
            safeExecute(plugin, "onFlowComplete") {
                plugin.onFlowComplete(flowName, success)
            }
        }
    }

    /**
     * Shutdown all plugins (cleanup resources).
     */
    fun shutdownAll() {
        plugins.forEach { plugin ->
            safeExecute(plugin, "onShutdown") {
                plugin.onShutdown()
            }
        }
    }

    /**
     * Execute a plugin hook safely, catching and logging any exceptions.
     * This ensures one plugin's errors don't crash the test or affect other plugins.
     */
    private fun safeExecute(plugin: MaestroPlugin, hookName: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.error(
                "Plugin '${plugin.name}' (${plugin.id}) failed in $hookName: ${e.message}",
                e
            )
        }
    }
}
