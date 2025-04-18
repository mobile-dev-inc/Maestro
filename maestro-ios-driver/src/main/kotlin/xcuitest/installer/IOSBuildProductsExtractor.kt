package xcuitest.installer

import org.rauschig.jarchivelib.ArchiverFactory
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import kotlin.io.path.isRegularFile

data class BuildProducts(val xctestRunPath: File, val uiRunnerPath: File)

class IOSBuildProductsExtractor(private val target: Path) {

    companion object {
        private val LOGGER = LoggerFactory.getLogger(IOSBuildProductsExtractor::class.java)
    }

    fun extract(sourceDirectory: String): BuildProducts {
        LOGGER.info("[Start] Writing build products")
        writeBuildProducts(sourceDirectory)
        LOGGER.info("[Done] Writing build products")

        LOGGER.info("[Start] Writing maestro-driver-iosUITests-Runner app")
        extractZipToApp("maestro-driver-iosUITests-Runner.zip")
        LOGGER.info("[Done] Writing maestro-driver-iosUITests-Runner app")

        LOGGER.info("[Start] Writing maestro-driver-ios app")
        extractZipToApp("maestro-driver-ios.zip")
        LOGGER.info("[Done] Writing maestro-driver-ios app")

        val targetFile = target.toFile()
        val xctestRun = targetFile.walkTopDown().firstOrNull { it.extension == "xctestrun" } ?: throw FileNotFoundException("xctestrun config does not exist")
        val uiRunner = targetFile.walkTopDown().firstOrNull { it.name == "maestro-driver-iosUITests-Runner.app" } ?: throw FileNotFoundException("ui test runner does not exist")

        return BuildProducts(
            xctestRunPath = xctestRun,
            uiRunnerPath = uiRunner
        )
    }

    private fun extractZipToApp(appFileName: String) {
        val appZip = target.toFile().walk().firstOrNull { it.name == appFileName } ?: throw FileNotFoundException("$appFileName not found")

        try {
            ArchiverFactory.createArchiver(appZip).apply {
                extract(appZip, appZip.parentFile)
            }
        } finally {
            Files.delete(appZip.toPath())
        }
    }

    private fun writeBuildProducts(sourceDirectory: String) {
        val uri = LocalXCTestInstaller::class.java.classLoader.getResource(sourceDirectory)?.toURI() ?: throw IllegalArgumentException("Resource not found: $sourceDirectory")

        val sourcePath = if (uri.scheme == "jar") {
            val fs = try {
                FileSystems.getFileSystem(uri)
            } catch (e: FileSystemNotFoundException) {
                FileSystems.newFileSystem(uri, emptyMap<String, Any>())
            }
            fs.getPath(sourceDirectory)
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
}