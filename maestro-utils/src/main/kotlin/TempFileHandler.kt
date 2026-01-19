package maestro.utils

import java.io.Closeable
import java.io.File
import java.nio.file.Files

// creates temporary files and directories and makes sure they get disposed
class TempFileHandler: Closeable {
    val tempFiles = mutableListOf<File>()

    fun createTempFile(prefix: String? = null, suffix: String? = null): File {
        val file = Files.createTempFile(prefix, suffix).toFile()
        file.deleteOnExit()
        tempFiles.add(file)
        return file
    }

    fun createTempDirectory(prefix: String? = null): File {
        val file = Files.createTempDirectory(prefix).toFile()
        file.deleteOnExit()
        tempFiles.add(file)
        return file
    }

    override fun close() {
        tempFiles.forEach {
            try {
                // if it's a directory, recursively clean it up
                it.deleteRecursively()
            } catch (_: Exception) {
            }
        }
    }

    // add a file for deletion
    fun addFile(logsFile: File) {
        tempFiles.add(logsFile)
    }
}