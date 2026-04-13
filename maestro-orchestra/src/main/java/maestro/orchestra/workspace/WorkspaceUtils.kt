package maestro.orchestra.workspace

import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
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

        val filesToInclude = if (!file.isDirectory()) {
            DependencyResolver.discoverAllDependencies(file)
        } else {
            Files.walk(file).filter { !it.isDirectory() }.toList()
        }
        val relativeTo = if (file.isDirectory()) file else findCommonAncestor(filesToInclude)
        createWorkspaceZipFromFiles(filesToInclude, relativeTo, out)

        // For single-file uploads, inject a synthetic config.yaml that restricts
        // execution to only the requested flow. Without this, the worker would
        // discover and run sibling flow files that ended up in the ZIP due to
        // the common ancestor being higher than the flow's parent directory.
        if (!file.isDirectory()) {
            val flowRelativePath = relativeTo.relativize(normalizePath(file)).toString()
            injectConfigYaml(out, flowRelativePath)
        }
    }

    private fun injectConfigYaml(zipPath: Path, flowRelativePath: String) {
        val zipUri = URI.create("jar:${zipPath.toUri()}")
        FileSystems.newFileSystem(zipUri, mapOf("create" to "false")).use { fs ->
            val configEntry = fs.getPath("config.yaml")
            val content = "flows:\n  - \"$flowRelativePath\"\n"
            Files.writeString(configEntry, content)
        }
    }

    private fun normalizePath(path: Path): Path {
        return try {
            path.toRealPath(LinkOption.NOFOLLOW_LINKS)
        } catch (e: Exception) {
            path.toAbsolutePath().normalize()
        }
    }

    internal fun findCommonAncestor(paths: List<Path>): Path {
        if (paths.isEmpty()) throw IllegalArgumentException("paths must not be empty")
        if (paths.size == 1) return normalizePath(paths.first()).parent

        val normalizedPaths = paths.map { normalizePath(it) }
        var ancestor = normalizedPaths.first().parent

        for (path in normalizedPaths.drop(1)) {
            ancestor = commonPrefix(ancestor, path.parent)
        }

        return ancestor
    }

    private fun commonPrefix(a: Path, b: Path): Path {
        val aRoot = a.root ?: throw IllegalArgumentException("Path must be absolute: $a")
        val bRoot = b.root ?: throw IllegalArgumentException("Path must be absolute: $b")
        if (aRoot != bRoot) throw IllegalArgumentException("Paths have different roots: $a and $b")

        val aParts = (0 until a.nameCount).map { a.getName(it) }
        val bParts = (0 until b.nameCount).map { b.getName(it) }

        var commonCount = 0
        for (i in 0 until minOf(aParts.size, bParts.size)) {
            if (aParts[i] == bParts[i]) commonCount++ else break
        }

        return if (commonCount == 0) aRoot else aRoot.resolve(aParts.subList(0, commonCount).joinToString(a.fileSystem.separator))
    }

    fun createWorkspaceZipFromFiles(files: List<Path>, relativeTo: Path, out: Path) {
        if (out.exists()) throw FileAlreadyExistsException(out.toFile())

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
