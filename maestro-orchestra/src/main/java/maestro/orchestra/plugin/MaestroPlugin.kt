package maestro.orchestra.plugin

import maestro.Maestro
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import java.nio.file.Path

/**
 * Base interface for Maestro plugins.
 *
 * Plugins can hook into Maestro's execution lifecycle to add custom functionality
 * like accessibility scanning, performance monitoring, or custom reporting.
 *
 * All lifecycle hook methods have default empty implementations - plugins only need
 * to override the hooks they care about.
 */
interface MaestroPlugin {

    /**
     * Unique identifier for this plugin (e.g., "aria-accessibility", "perf-monitor")
     */
    val id: String

    /**
     * Human-readable name for this plugin (e.g., "ARIA Accessibility Scanner")
     */
    val name: String

    /**
     * Called once when the plugin is loaded, before any flows execute.
     * Use this to initialize resources, validate configuration, etc.
     *
     * @param context Plugin initialization context with access to Maestro and configuration
     */
    fun onInit(context: MaestroContext) {}

    /**
     * Called when a flow starts, before any commands execute.
     *
     * @param commands The list of commands that will be executed in this flow
     */
    fun onFlowStart(commands: List<MaestroCommand>) {}

    /**
     * Called before each command executes.
     *
     * @param index The zero-based index of the command in the flow
     * @param command The command that is about to execute
     */
    fun onCommandStart(index: Int, command: MaestroCommand) {}

    /**
     * Called after each command completes successfully.
     *
     * @param index The zero-based index of the command in the flow
     * @param command The command that just executed
     */
    fun onCommandComplete(index: Int, command: MaestroCommand) {}

    /**
     * Called when a command fails with an exception.
     *
     * @param index The zero-based index of the command in the flow
     * @param command The command that failed
     * @param error The exception that was thrown
     */
    fun onCommandFailed(index: Int, command: MaestroCommand, error: Throwable) {}

    /**
     * Called when a command is skipped (e.g., due to conditional logic).
     *
     * @param index The zero-based index of the command in the flow
     * @param command The command that was skipped
     */
    fun onCommandSkipped(index: Int, command: MaestroCommand) {}

    /**
     * Called when a flow completes (success or failure).
     *
     * @param config The flow configuration (contains name, tags, appId, ext, etc.). May be null if no config was provided.
     * @param success True if the flow completed successfully, false if it failed
     */
    fun onFlowComplete(config: MaestroConfig?, success: Boolean) {}

    /**
     * Called when the plugin is being unloaded (e.g., CLI shutdown).
     * Use this to clean up resources, close connections, etc.
     */
    fun onShutdown() {}
}
