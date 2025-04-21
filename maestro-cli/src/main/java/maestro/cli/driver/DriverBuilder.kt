package maestro.cli.driver

import java.io.File
import java.nio.file.*
import java.util.concurrent.TimeUnit
import kotlin.io.path.pathString

class DriverBuilder {

    fun buildDriver(config: DriverBuildConfig): Path {
        // Get driver source from resources
        val driverSourcePath = getDriverSourceFromResources(config)

        // Create temporary build directory
        val workingDirectory = Paths.get(System.getProperty("user.home"), ".maestro")
        val buildDir = Files.createDirectories(workingDirectory.resolve("maestro-iphoneos-driver-build"))
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
            val process = ProcessBuilder(
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
            ).directory(workingDirectory.toFile())
                .redirectOutput(outputFile)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(120, TimeUnit.SECONDS)

            if (process.exitValue() != 0) {
                throw RuntimeException("Failed to build driver: $output")
            }

            // Return path to build products
            return derivedDataPath.resolve("Build/Products")
        } finally {
            xcodebuildOutput.toFile().deleteRecursively()
        }
    }

    private fun getDriverSourceFromResources(config: DriverBuildConfig): Path {
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