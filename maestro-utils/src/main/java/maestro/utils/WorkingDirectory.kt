package maestro.utils

import java.io.File

object WorkingDirectory {
    var baseDir: File = File(System.getProperty("user.dir"))
    var pathAliases: Map<String, String> = emptyMap()

    fun resolve(path: String): File {
        val resolvedPath = resolveAlias(path)
        val file = File(resolvedPath)
        return if (file.isAbsolute) file else File(baseDir, resolvedPath)
    }
    
    fun resolve(file: File): File = if (file.isAbsolute) file else File(baseDir, file.path)

    private fun resolveAlias(path: String): String {
        for ((alias, target) in pathAliases) {
            if (path.startsWith("$alias/")) {
                return path.replaceFirst(alias, target)
            }
            if (path == alias) {
                return target
            }
        }
        return path
    }
}

