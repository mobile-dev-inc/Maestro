package maestro.orchestra.util

import maestro.orchestra.error.InvalidFlowFile
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

internal object FileReader {

    fun readFile(currentFlow: Path, fileToRead: String): String {
        return resolvePath(currentFlow, fileToRead).readText()
    }

    // Same as `resolvePath` in `YamlFluentCommand.kt`, which is now deprecated.
    fun resolvePath(currentFlow: Path, fileToRead: String): Path {
        val path = currentFlow.fileSystem.getPath(fileToRead)

        val resolvedPath = if (path.isAbsolute) path
                        else currentFlow.resolveSibling(path).toAbsolutePath()

        if (resolvedPath.equals(currentFlow.toAbsolutePath())) {
            throw InvalidFlowFile(
                "Referenced file can't be the same as the main Flow file: ${resolvedPath.toUri()}",
                resolvedPath
            )
        }

        if (!resolvedPath.exists()) {
            throw InvalidFlowFile("File does not exist: ${resolvedPath.toUri()}", resolvedPath)
        }

        if (resolvedPath.isDirectory()) {
            throw InvalidFlowFile("File can't be a directory: ${resolvedPath.toUri()}", resolvedPath)
        }

        return resolvedPath
    }
}
