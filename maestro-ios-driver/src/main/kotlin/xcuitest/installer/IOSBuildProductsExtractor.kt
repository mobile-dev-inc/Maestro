package xcuitest.installer

import maestro.utils.TempFileHandler
import org.rauschig.jarchivelib.ArchiverFactory
import org.slf4j.LoggerFactory
import util.IOSDeviceType
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystemAlreadyExistsException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.isRegularFile

data class BuildProducts(val xctestRunPath: File, val uiRunnerPath: File)

enum class Context {
    CLI,
    CLOUD
}

class IOSBuildProductsExtractor(
    private val target: Path,
    private val context: Context,
    private val deviceType: IOSDeviceType,
) {

    companion object {
        private val LOGGER = LoggerFactory.getLogger(IOSBuildProductsExtractor::class.java)
    }

    fun extract(sourceDirectory: String): BuildProducts {
        val targetFile = target.toFile()
        val hashFile = File(targetFile, ".source-hash")
        val sourceHash = computeSourceHash(sourceDirectory)

        if (sourceHash != null && hashFile.exists() && hashFile.readText() == sourceHash) {
            val xctestRun = targetFile.walkTopDown().firstOrNull { it.extension == "xctestrun" }
            val uiRunner = targetFile.walkTopDown().firstOrNull { it.name == "maestro-driver-iosUITests-Runner.app" }
            if (xctestRun != null && uiRunner != null) {
                LOGGER.info("Build products cached and up to date, skipping extraction")
                return BuildProducts(xctestRunPath = xctestRun, uiRunnerPath = uiRunner)
            }
        }

        LOGGER.info("[Start] Writing build products")
        writeBuildProducts(sourceDirectory)
        LOGGER.info("[Done] Writing build products")

        LOGGER.info("[Start] Writing maestro-driver-iosUITests-Runner app")
        extractZipToApp("maestro-driver-iosUITests-Runner.zip")
        LOGGER.info("[Done] Writing maestro-driver-iosUITests-Runner app")

        LOGGER.info("[Start] Writing maestro-driver-ios app")
        extractZipToApp("maestro-driver-ios.zip")
        LOGGER.info("[Done] Writing maestro-driver-ios app")

        if (sourceHash != null) {
            hashFile.writeText(sourceHash)
        }

        val xctestRun = targetFile.walkTopDown().firstOrNull { it.extension == "xctestrun" }
            ?: throw FileNotFoundException("xctestrun config does not exist")
        val uiRunner = targetFile.walkTopDown().firstOrNull { it.name == "maestro-driver-iosUITests-Runner.app" }
            ?: throw FileNotFoundException("ui test runner does not exist")

        return BuildProducts(
            xctestRunPath = xctestRun,
            uiRunnerPath = uiRunner
        )
    }

    private fun computeSourceHash(sourceDirectory: String): String? {
        return try {
            val uri = LocalXCTestInstaller::class.java.classLoader.getResource(sourceDirectory)?.toURI()
                ?: return null

            val sourcePath = if (uri.scheme == "jar") {
                val fs = try {
                    FileSystems.getFileSystem(uri)
                } catch (e: FileSystemNotFoundException) {
                    uri.getOrCreateFileSystem()
                }
                fs.getPath(sourceDirectory)
            } else {
                Paths.get(uri)
            }

            val digest = MessageDigest.getInstance("SHA-256")
            Files.walk(sourcePath).use { paths ->
                paths.filter { it.isRegularFile() }.sorted().forEach { file ->
                    digest.update(file.fileName.toString().toByteArray())
                    Files.newInputStream(file).use { input ->
                        val buffer = ByteArray(8192)
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            digest.update(buffer, 0, bytesRead)
                        }
                    }
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            LOGGER.info("Could not compute source hash, will extract fresh: ${e.message}")
            null
        }
    }

    private fun extractZipToApp(appFileName: String) {
        val appZip = target.toFile().walk().firstOrNull { it.name == appFileName && it.extension == "zip" }
            ?: run {
                LOGGER.info("zip extension not present in the target directory, skipping unzipping operation.")
                return
            }

        try {
            ArchiverFactory.createArchiver(appZip).apply {
                extract(appZip, appZip.parentFile)
            }
        } finally {
            Files.delete(appZip.toPath())
        }
    }

    private fun writeBuildProducts(sourceDirectory: String) {
        val uri = when  {
            deviceType == IOSDeviceType.SIMULATOR -> {
                LocalXCTestInstaller::class.java.classLoader.getResource(sourceDirectory)?.toURI()
                    ?: throw IllegalArgumentException("Resource not found: $sourceDirectory")
            }
            context == Context.CLI && deviceType == IOSDeviceType.REAL -> {
                Paths.get(sourceDirectory).toUri()
            }
            else ->  {
                LocalXCTestInstaller::class.java.classLoader.getResource(sourceDirectory)?.toURI()
                    ?: throw IllegalArgumentException("Resource not found: $sourceDirectory")
            }
        }

        val sourcePath = if (uri.scheme == "jar") {
            when (deviceType) {
                IOSDeviceType.REAL -> {
                    Paths.get(uri)
                }
                IOSDeviceType.SIMULATOR -> {
                    val fs = try {
                        FileSystems.getFileSystem(uri)
                    } catch (e: FileSystemNotFoundException) {
                        uri.getOrCreateFileSystem()
                    }
                    fs.getPath(sourceDirectory)
                }
            }
        } else {
            Paths.get(uri)
        }

        Files.walk(sourcePath).use { paths ->
            paths.filter { it.isRegularFile() }.forEach { file ->
                val relative = sourcePath.relativize(file)
                val targetPath = target.resolve(relative.toString())

                Files.createDirectories(targetPath.parent)
                Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    private fun URI.getOrCreateFileSystem(): FileSystem {
        return try {
            FileSystems.newFileSystem(this, emptyMap<String, Any>())
        } catch (e: FileSystemAlreadyExistsException) {
            FileSystems.getFileSystem(this)
        }
    }
}
