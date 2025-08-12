package maestro.cli.util

import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.streams.toList

object WorkspaceUtils {

    fun createWorkspaceZip(file: Path, out: Path) {
        if (!file.exists()) throw FileNotFoundException(file.absolutePathString())
        if (out.exists()) throw FileAlreadyExistsException(out.toFile())
        val files = Files.walk(file).filter { !it.isDirectory() }.toList()
        val relativeTo = if (file.isDirectory()) file else file.parent

        val outUri = URI.create("jar:${out.toUri()}")
        FileSystems.newFileSystem(outUri, mapOf("create" to "true")).use { fs ->
            files.forEach {
                val outPath = fs.getPath(relativeTo.relativize(it).toString())
                if (outPath.parent != null) {
                    Files.createDirectories(outPath.parent)
                }
                it.copyTo(outPath)
            }
        }
    }
}
