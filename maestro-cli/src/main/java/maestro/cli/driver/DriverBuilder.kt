package maestro.cli.driver

import maestro.MaestroException
import maestro.utils.TempFileHandler
import java.io.File
import java.nio.file.*
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.io.path.pathString

class DriverBuilder(private val processBuilderFactory: XcodeBuildProcessBuilderFactory = XcodeBuildProcessBuilderFactory()) {
    private val waitTime: Long by lazy {
        System.getenv("MAESTRO_XCODEBUILD_WAIT_TIME")?.toLongOrNull() ?: DEFAULT_XCODEBUILD_WAIT_TIME
    }

    /**
     * Builds the iOS driver for real iOS devices by extracting the driver source, copying it to a temporary build
     * directory, and executing the Xcode build process. The resulting build products are placed in the specified
     * derived data path.
     *
     * @param config A configuration object containing details like team ID, derived data path, destination platform,
     *               architectures, and other parameters required for building the driver.
     * @return The path to the directory containing build products.
     * @throws RuntimeException if the build process fails.
     *
     * Directory Structure:
     *   1. workingDirectory (Path): Root working directory for Maestro stored in the user's home directory.
     *      .maestro
     *      |_ maestro-iphoneos-driver-build
     *         |_ driver-iphoneos: Consists the build products to setup iOS driver: maestro-driver-*.xctestrun,
     *            Debug-iphoneos/maestro-driver-iosUITests-Runner.app, and Debug-iphoneos/maestro-driver-ios.app
     *         |_ output.log: In case of errors output.log would be there to help debug
     *
     *   2. xcodebuildOutput (Path): A temporary directory created to store the output logs of the xcodebuild process and source code.
     *      It exists only for the duration of the build operation.
     *      e.g., $TMPDIR/maestro-xcodebuild-outputXXXXXX
     */
    fun buildDriver(config: DriverBuildConfig): Path {
        // Get driver source from resources
        val driverSourcePath = getDriverSourceFromResources(config)

        // Create temporary build directory
        val workingDirectory = Paths.get(config.sourceCodeRoot, ".maestro")
        val buildDir = Files.createDirectories(workingDirectory.resolve("maestro-iphoneos-driver-build")).apply {
            // Cleanup directory before we execute the build
            toFile().deleteRecursively()
        }
        val tempFileHandler = TempFileHandler()
        val xcodebuildOutput = tempFileHandler.createTempDirectory("maestro-xcodebuild-output").toPath()
        val outputFile = xcodebuildOutput.resolve("output.log").toFile()
        var cleanupBuildSource = true

        try {
            // Copy driver source to build directory
            Files.walk(driverSourcePath).use { paths ->
                paths.filter { Files.isRegularFile(it) }.forEach { path ->
                    val targetPath = xcodebuildOutput.resolve(driverSourcePath.relativize(path).toString())
                    Files.createDirectories(targetPath.parent)
                    Files.copy(path, targetPath, StandardCopyOption.REPLACE_EXISTING)
                }
            }

            // Create derived data path
            val derivedDataPath = buildDir.resolve(config.derivedDataPath)
            Files.createDirectories(derivedDataPath)

            // Build command
            val process = processBuilderFactory.createProcess(
                commands = listOf(
                    "xcodebuild",
                    "clean",
                    "build-for-testing",
                    "-project", "${xcodebuildOutput.pathString}/maestro-driver-ios.xcodeproj",
                    "-scheme", "maestro-driver-ios",
                    "-destination", config.destination,
                    "-allowProvisioningUpdates",
                    "-derivedDataPath", derivedDataPath.toString(),
                    "DEVELOPMENT_TEAM=${config.teamId}",
                    "ARCHS=${config.architectures}",
                    "CODE_SIGN_IDENTITY=$CODE_SIGN_IDENTITY",
                    // Works around an intermittent Xcode codesign race where the framework is
                    // signed before its binary finishes linking.
                    "EAGER_LINKING=NO",
                ), workingDirectory = workingDirectory.toFile(), outputFile = outputFile
            )

            val finished = try {
                process.waitFor(waitTime, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                cleanupBuildSource = terminateProcess(process)
                Thread.currentThread().interrupt()
                throw e
            }
            if (!finished) {
                cleanupBuildSource = terminateProcess(process)
                throw buildFailure(
                    buildDir = buildDir,
                    outputFile = outputFile,
                    detail = if (cleanupBuildSource) {
                        "xcodebuild timed out after $waitTime seconds"
                    } else {
                        "xcodebuild timed out after $waitTime seconds and could not be terminated"
                    },
                )
            }

            if (process.exitValue() != 0) {
                throw buildFailure(
                    buildDir = buildDir,
                    outputFile = outputFile,
                    detail = "xcodebuild exited with code ${process.exitValue()}",
                )
            }

            val buildProducts = derivedDataPath.resolve("Build/Products")
            validateBuildProducts(buildProducts, config, buildDir, outputFile)
            writeVersionMarker(buildDir, config)
            return buildProducts
        } finally {
            // Never remove the copied Xcode project from beneath a process that resisted
            // forcible termination. TempFileHandler's delete-on-exit remains the fallback.
            if (cleanupBuildSource) tempFileHandler.close()
        }
    }

    private fun validateBuildProducts(
        buildProducts: Path,
        config: DriverBuildConfig,
        buildDir: Path,
        outputFile: File,
    ) {
        val missing = missingBuildProducts(buildProducts, config.configuration)
        if (missing.isNotEmpty()) {
            throw buildFailure(
                buildDir = buildDir,
                outputFile = outputFile,
                detail = "xcodebuild completed without required products: ${missing.joinToString()}",
            )
        }
    }

    private fun writeVersionMarker(buildDir: Path, config: DriverBuildConfig) {
        val marker = buildDir.resolve("version.properties")
        val pendingMarker = buildDir.resolve("version.properties.pending")
        try {
            Files.newBufferedWriter(pendingMarker).use {
                val properties = Properties()
                properties[MARKER_VERSION] = config.cliVersion.toString()
                properties[MARKER_TEAM_ID] = config.teamId
                properties[MARKER_DESTINATION] = config.destination
                properties[MARKER_CODE_SIGN_IDENTITY] = CODE_SIGN_IDENTITY
                properties[MARKER_ARCHITECTURES] = config.architectures
                properties[MARKER_CONFIGURATION] = config.configuration
                properties.store(it, null)
            }
            try {
                Files.move(
                    pendingMarker,
                    marker,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(pendingMarker, marker, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(pendingMarker)
        }
    }

    private fun buildFailure(buildDir: Path, outputFile: File, detail: String): MaestroException.IOSDeviceDriverSetupException {
        val targetErrorFile = buildDir.resolve(outputFile.name).toFile()
        if (outputFile.exists()) {
            outputFile.copyTo(targetErrorFile, overwrite = true)
        } else {
            targetErrorFile.writeText(detail)
        }
        return MaestroException.IOSDeviceDriverSetupException(
            """
                Failed to build iOS driver for connected iOS device: $detail.

                Error details:
                - Build log: ${targetErrorFile.path}
            """.trimIndent()
        )
    }

    private fun terminateProcess(process: Process): Boolean {
        var interrupted = false
        try {
            process.destroy()
            val stopped = try {
                process.waitFor(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }
            if (stopped) return true

            process.destroyForcibly()
            return try {
                process.waitFor(PROCESS_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun getDriverSourceFromResources(config: DriverBuildConfig): Path {
        val resourcePath = config.sourceCodePath
        val resourceUrl = DriverBuilder::class.java.classLoader.getResource(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")
        val uri = resourceUrl.toURI()

        val path = if (uri.scheme == "jar") {
            val fs = try {
                FileSystems.getFileSystem(uri)
            } catch (e: FileSystemNotFoundException) {
                FileSystems.newFileSystem(uri, emptyMap<String, Any>())
            }
            fs.getPath("/$resourcePath")
        } else {
            Paths.get(uri)
        }
        return path
    }

    companion object {
        private const val DEFAULT_XCODEBUILD_WAIT_TIME: Long = 120
        private const val PROCESS_TERMINATION_TIMEOUT_SECONDS: Long = 5

        internal const val CODE_SIGN_IDENTITY = "Apple Development"
        internal const val MARKER_VERSION = "version"
        internal const val MARKER_TEAM_ID = "teamId"
        internal const val MARKER_DESTINATION = "destination"
        internal const val MARKER_CODE_SIGN_IDENTITY = "codeSignIdentity"
        internal const val MARKER_ARCHITECTURES = "architectures"
        internal const val MARKER_CONFIGURATION = "configuration"

        internal fun missingBuildProducts(buildProducts: Path, configuration: String): List<String> {
            val xctestRunExists = buildProducts.toFile().walkTopDown().any {
                it.isFile &&
                        it.extension == "xctestrun" &&
                        it.name.startsWith("maestro-driver-ios_") &&
                        it.length() > 0
            }
            val configurationDir = buildProducts.resolve("$configuration-iphoneos")
            val runnerExists = isCompleteAppBundle(
                configurationDir.resolve("maestro-driver-iosUITests-Runner.app"),
                "maestro-driver-iosUITests-Runner",
            )
            val appExists = isCompleteAppBundle(
                configurationDir.resolve("maestro-driver-ios.app"),
                "maestro-driver-ios",
            )

            return buildList {
                if (!xctestRunExists) add("a non-empty maestro-driver-ios .xctestrun file")
                if (!runnerExists) add("a complete maestro-driver-iosUITests-Runner.app")
                if (!appExists) add("a complete maestro-driver-ios.app")
            }
        }

        private fun isCompleteAppBundle(bundle: Path, executableName: String): Boolean =
            bundle.toFile().isDirectory &&
                    bundle.resolve("Info.plist").toFile().let { it.isFile && it.length() > 0 } &&
                    bundle.resolve(executableName).toFile().let { it.isFile && it.length() > 0 }
    }
}
