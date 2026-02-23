package maestro.plugins

import maestro.Maestro
import maestro.js.JsEngine
import maestro.orchestra.MaestroConfig
import maestro.utils.Insights
import okhttp3.OkHttpClient
import java.nio.file.Path

/**
 * Context provided to plugin commands during execution.
 * Contains all the necessary dependencies and utilities for command execution.
 */
data class PluginExecutionContext(
    /**
     * The main Maestro instance for interacting with the device/simulator.
     */
    val maestro: Maestro,
    
    /**
     * JavaScript engine for script evaluation and variable management.
     */
    val jsEngine: JsEngine,
    
    /**
     * Current flow configuration.
     */
    val config: MaestroConfig?,
    
    /**
     * HTTP client for making network requests.
     */
    val httpClient: OkHttpClient?,
    
    /**
     * Directory for storing screenshots (if configured).
     */
    val screenshotsDir: Path?,
    
    /**
     * Insights collector for reporting issues and analytics.
     */
    val insights: Insights,
    
    /**
     * Lookup timeout in milliseconds for finding elements.
     */
    val lookupTimeoutMs: Long,
    
    /**
     * Optional lookup timeout in milliseconds for optional operations.
     */
    val optionalLookupTimeoutMs: Long,
    
    /**
     * Copied text from clipboard operations.
     */
    val copiedText: String?,
    
    /**
     * Set copied text for clipboard operations.
     */
    val setCopiedText: (String?) -> Unit
)
