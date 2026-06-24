package maestro.orchestra.plugin

import maestro.Maestro
import maestro.orchestra.MaestroConfig
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
     * The flow configuration from the YAML file.
     *
     * Contains:
     * - appId: The application ID being tested
     * - name: The flow name
     * - tags: Tags for categorizing flows (e.g., ["smoke", "production"])
     * - ext: Custom plugin-specific configuration
     * - properties: Flow properties
     *
     * May be null if no config was provided.
     */
    val config: MaestroConfig?,

    /**
     * Optional directory where Maestro saves screenshots.
     * May be null if screenshot saving is disabled.
     */
    val screenshotsDir: Path?
)
