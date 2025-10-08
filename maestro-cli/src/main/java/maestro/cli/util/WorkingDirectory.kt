package maestro.cli.util

import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.workspace.PathResolver
import java.io.File

object WorkingDirectory {
    var baseDir: File = File(System.getProperty("user.dir"))
    var workspaceConfig: WorkspaceConfig? = null

    fun resolve(path: String): File {
        val resolvedPath = workspaceConfig?.let {
            PathResolver.resolve(path, it)
        } ?: path
        return File(baseDir, resolvedPath)
    }
    
    fun resolve(file: File): File = if (file.isAbsolute) file else File(baseDir, file.path)
}
