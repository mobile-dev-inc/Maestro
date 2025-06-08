package maestro.cli.driver

import maestro.MaestroException
import java.io.File
import java.nio.file.*
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.io.path.pathString

class DriverBuilder(private val processBuilderFactory: XcodeBuildProcessBuilderFactory = XcodeBuildProcessBuilderFactory()) {

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
        val xcodebuildOutput = Files.createTempDirectory("maestro-xcodebuild-output")
        val outputFile = File(xcodebuildOutput.pathString + "/output.log")

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
                    "CODE_SIGN_IDENTITY=Apple Development",
                ), workingDirectory = workingDirectory.toFile(), outputFile = outputFile
            )

            process.waitFor(120, TimeUnit.SECONDS)

            if (process.exitValue() != 0) {
                // copy the error log inside driver output
                val targetErrorFile = File(buildDir.toFile(), outputFile.name)
                outputFile.copyTo(targetErrorFile, overwrite = true)
                throw MaestroException.IOSDeviceDriverSetupException(
                    """
                        
                        Failed to build iOS driver for connected iOS device.
                        
                        Error details:
                        - Build log: ${targetErrorFile.path}
                    """.trimIndent()
                )
            }

            // Return path to build products
            return derivedDataPath.resolve("Build/Products")
        } finally {
            File(buildDir.toFile(), "version.properties").writer().use {
                val p = Properties()
                p["version"] = config.cliVersion.toString()
                p.store(it, null)
            }
            xcodebuildOutput.toFile().deleteRecursively()
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
}