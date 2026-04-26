package maestro.orchestra.plugin

import maestro.Maestro
import java.nio.file.Path

/**
 * Context provided to plugins during initialization.
 *
 * Contains access to Maestro's device driver, output directories,
 * and plugin-specific configuration.
 */
data class MaestroContext(
    /**
     * The Maestro driver instance for interacting with the device.
     *
     * Plugins can use this to:
     * - Query view hierarchy: maestro.viewHierarchy()
     * - Take screenshots: maestro.takeScreenshot()
     * - Get device info: maestro.deviceInfo
     */
    val maestro: Maestro,

    /**
     * Directory where the plugin can write output files (reports, screenshots, etc.)
     *
     * This directory is created by Maestro and is typically under the test output directory.
     */
    val outputDirectory: Path,

    /**
     * Plugin-specific configuration provided by the user.
     *
     * Configuration can come from:
     * - Environment variables
     * - Config files (.maestro/config.yaml)
     * - CLI flags
     *
     * Example: For ARIA plugin, might contain {"severity": "error", "outputFormat": "html"}
     */
    val config: Map<String, Any>,

    /**
     * Optional directory where Maestro saves screenshots.
     * May be null if screenshot saving is disabled.
     */
    val screenshotsDir: Path?
)
