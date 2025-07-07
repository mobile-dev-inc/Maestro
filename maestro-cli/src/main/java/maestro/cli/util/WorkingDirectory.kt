package maestro.cli.util

import java.io.File

object WorkingDirectory {
    var baseDir: File = File(System.getProperty("user.dir"))

    fun resolve(path: String): File = File(baseDir, path)
    fun resolve(file: File): File = if (file.isAbsolute) file else File(baseDir, file.path)
}
