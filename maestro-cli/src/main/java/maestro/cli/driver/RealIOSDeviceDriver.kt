package maestro.cli.driver

import maestro.MaestroException
import maestro.cli.api.CliVersion
import maestro.cli.util.EnvUtils
import maestro.cli.util.PrintUtils.message
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Properties

class RealIOSDeviceDriver(private val teamId: String?, private val destination: String, private val driverBuilder: DriverBuilder) {

    fun validateAndUpdateDriver(driverRootDirectory: Path = getDefaultVersionPropertiesFile(), force: Boolean = false) {
        val driverDirectory = driverRootDirectory.resolve("maestro-iphoneos-driver-build")
        val versionPropertiesFile = driverDirectory.resolve("version.properties")

        val currentCliVersion = EnvUtils.CLI_VERSION ?: throw IllegalStateException("CLI version is unavailable.")

        if (force) {
            buildDriver(driverDirectory, message = "Building iOS driver for $destination...")
            return
        }

        if (Files.exists(versionPropertiesFile)) {
            val properties = Properties().apply {
                Files.newBufferedReader(versionPropertiesFile).use(this::load)
            }
            val localVersion = properties.getProperty("version")?.let { CliVersion.parse(it) }
                ?: throw IllegalStateException("Invalid or missing version in version.properties.")

            val products = driverDirectory.resolve("driver-iphoneos").resolve("Build").resolve("Products")
            val cacheIdentityMatches = properties.getProperty(DriverBuilder.MARKER_DESTINATION) == destination &&
                    properties.getProperty(DriverBuilder.MARKER_CODE_SIGN_IDENTITY) == DriverBuilder.CODE_SIGN_IDENTITY &&
                    properties.getProperty(DriverBuilder.MARKER_ARCHITECTURES) == DRIVER_ARCHITECTURES &&
                    properties.getProperty(DriverBuilder.MARKER_CONFIGURATION) == DRIVER_CONFIGURATION &&
                    !properties.getProperty(DriverBuilder.MARKER_TEAM_ID).isNullOrBlank() &&
                    (teamId == null || properties.getProperty(DriverBuilder.MARKER_TEAM_ID) == teamId)
            val productsMissing = DriverBuilder.missingBuildProducts(products, DRIVER_CONFIGURATION)
            if (currentCliVersion > localVersion) {
                message("Local version $localVersion of iOS driver is outdated. Updating to latest.")
                buildDriver(driverDirectory, message = "Validating and updating iOS driver for real iOS device: $destination...")
            } else if (!cacheIdentityMatches) {
                message("Cached iOS driver signing or destination does not match this session; rebuilding.")
                buildDriver(driverDirectory, message = "Building the drivers for $destination")
            } else if (productsMissing.isNotEmpty()) {
                message("Cached drivers for $destination are incomplete (${productsMissing.joinToString()}); rebuilding.")
                buildDriver(driverDirectory, message = "Building the drivers for $destination")
            }
        } else {
            buildDriver(driverDirectory, "Building iOS driver for $destination...")
        }
    }

    private fun buildDriver(driverDirectory: Path, message: String) {
        val spinner = Spinner(message).apply {
            start()
        }
        try {
            // Build the new driver
            val teamId = try {
                requireNotNull(teamId) { "Apple account team ID must be specified." }
            } catch (e: IllegalArgumentException) {
                throw MaestroException.MissingAppleTeamId(
                    "Apple account team ID must be specified to build drivers for connected iPhone."
                )
            }

            // Cleanup old driver files if necessary
            if (Files.exists(driverDirectory)) {
                message("Cleaning up old driver files...")
                driverDirectory.toFile().deleteRecursively()
            }

            driverBuilder.buildDriver(
                DriverBuildConfig(
                    teamId = teamId,
                    derivedDataPath = "driver-iphoneos",
                    destination = destination,
                    sourceCodePath = "driver/ios",
                    cliVersion = EnvUtils.CLI_VERSION,
                    architectures = DRIVER_ARCHITECTURES,
                    configuration = DRIVER_CONFIGURATION,
                )
            )
        } finally {
            spinner.stop()
        }
        message("✅ Drivers successfully set up for destination $destination")
    }

    private fun getDefaultVersionPropertiesFile(): Path {
        val maestroDirectory = Paths.get(System.getProperty("user.home"), ".maestro")
        return maestroDirectory
    }

    companion object {
        private const val DRIVER_ARCHITECTURES = "arm64"
        private const val DRIVER_CONFIGURATION = "Debug"
    }
}
