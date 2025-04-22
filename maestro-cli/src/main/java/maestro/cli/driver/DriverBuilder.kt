package maestro.cli.driver

import java.io.File
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlin.io.path.pathString

class DriverBuilder(private val processBuilderFactory: XcodeBuildProcessBuilderFactory = XcodeBuildProcessBuilderFactory()) {

    fun buildDriver(config: DriverBuildConfig): Path {
        // Get driver source from resources
        val driverSourcePath = getDriverSourceFromResources(config)

        // Create temporary build directory
        val workingDirectory = Paths.get(System.getProperty("user.home"), ".maestro")
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
                ), workingDirectory = workingDirectory.toFile()
            ).redirectOutput(outputFile)
                .redirectError(outputFile)
                .start()

            process.waitFor(120, TimeUnit.SECONDS)

            if (process.exitValue() != 0) {
                // copy the error log inside driver output
                val targetErrorFile = File(buildDir.toFile(), outputFile.name)
                outputFile.copyTo(targetErrorFile, overwrite = true)
                throw RuntimeException("Failed to build driver, output log on ${targetErrorFile.path}")
            }

            // Return path to build products
            return derivedDataPath.resolve("Build/Products")
        } finally {
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