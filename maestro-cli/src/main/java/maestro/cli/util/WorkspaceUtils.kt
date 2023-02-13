package maestro.cli.util

import maestro.cli.CliError
import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.copyTo
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.streams.toList

object WorkspaceUtils {

    fun createWorkspaceZip(file: Path, out: Path) {
        if (!file.exists()) throw FileNotFoundException(file.absolutePathString())
        if (out.exists()) throw FileAlreadyExistsException(out.toFile())
        val files = Files.walk(file).filter { !it.isDirectory() }.toList()
        val relativeTo = if (file.isDirectory()) file else file.parent

        val outUri = URI.create("jar:file:${out.toAbsolutePath()}")
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

    fun createMaestroMockServerWorkspaceZip(file: Path, out: Path) {
        if (!file.exists()) throw FileNotFoundException(file.absolutePathString())
        if (out.exists()) throw FileAlreadyExistsException(out.toFile())
        val files = Files.walk(file).filter { !it.isDirectory() && it.name.endsWith(".js") }.toList()
        val relativeTo = if (file.isDirectory()) file else file.parent

        if (files.isEmpty()) {
            throw CliError("No Javascript files with mock rules found in workspace: ${file.absolutePathString()}")
        }

        if (files.none { it.name == "index.js" }) {
            throw CliError("No index.js file found in workspace: ${file.absolutePathString()}")
        }

        val outUri = URI.create("jar:file:${out.toAbsolutePath()}")
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
