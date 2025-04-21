package maestro.cli.driver

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class DriverBuilder {

    fun buildDriver(config: DriverBuildConfig): Path {
        // Get driver source from resources
        val driverSource = getDriverSourceFromResources(config)

        // Create temporary build directory
        val workingDirectory = Paths.get(System.getProperty("user.home"), ".maestro")
        val buildDir = Files.createDirectories(workingDirectory.resolve("maestro-iphoneos-driver-build"))

        // Copy driver source to build directory
        driverSource.copyTo(buildDir.toFile(), overwrite = true)

        // Create derived data path
        val derivedDataPath = buildDir.resolve(config.derivedDataPath)
        Files.createDirectories(derivedDataPath)

        // Build command
        val process = ProcessBuilder(
            "xcodebuild",
            "clean",
            "build-for-testing",
            "-project", "${driverSource.path}/maestro-driver-ios.xcodeproj",
            "-scheme", "maestro-driver-ios",
            "-destination", config.destination,
            "-allowProvisioningUpdates",
            "-derivedDataPath", derivedDataPath.toString(),
            "DEVELOPMENT_TEAM=${config.teamId}",
            "ARCHS=${config.architectures}",
            "CODE_SIGN_IDENTITY=Apple Development",
        ).directory(workingDirectory.toFile()).redirectError(ProcessBuilder.Redirect.PIPE).start()

        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(120, TimeUnit.SECONDS)

        if (process.exitValue() != 0) {
            throw RuntimeException("Failed to build driver: $output")
        }

        // Return path to build products
        return derivedDataPath.resolve("Build/Products")
    }

    private fun getDriverSourceFromResources(config: DriverBuildConfig): File {
        val resourcePath = config.sourceCodePath
        val resourceUrl = DriverBuilder::class.java.classLoader.getResource(resourcePath)
            ?: throw IllegalArgumentException("Resource not found: $resourcePath")
        val uri = resourceUrl.toURI()

        val path = if (uri.scheme == "jar") {
            val fs = FileSystems.getFileSystem(uri)
            fs.getPath(resourcePath)
        } else {
            Paths.get(uri)

        }
        return path.toFile()
    }
}