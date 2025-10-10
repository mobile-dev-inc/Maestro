package maestro.orchestra.workspace

import maestro.orchestra.WorkspaceConfig
import java.io.File

object PathResolver {
    
        fun resolve(path: String, workspaceConfig: WorkspaceConfig?): String {
            val pathAliases = workspaceConfig?.pathAliases ?: return path
        
        // Find the first matching pathAlias and replace it
        for ((pathAlias, aliasPath) in pathAliases) {
            if (path.startsWith(pathAlias)) {
                val remainingPath = path.substring(pathAlias.length)
                return "$aliasPath$remainingPath"
            }
        }
        
        return path
    }
    
        fun validate(workspaceConfig: WorkspaceConfig?, baseDir: File): List<String> {
            val errors = mutableListOf<String>()
            
            val pathAliasMap = workspaceConfig?.pathAliases ?: return errors
        
        // Check for circular references
        for ((pathAlias, aliasPath) in pathAliasMap) {
            if (aliasPath.startsWith("@") || aliasPath.startsWith("!") || aliasPath.startsWith("~")) {
                errors.add("PathAlias '$pathAlias' references another pathAlias '$aliasPath'. Circular references are not supported.")
            }
        }
        
        // Check that pathAlias paths exist
        for ((pathAlias, aliasPath) in pathAliasMap) {
            val resolvedPath = if (aliasPath.startsWith("/") || aliasPath.matches(Regex("^[A-Za-z]:.*"))) {
                // Absolute path
                File(aliasPath)
            } else {
                // Relative path
                File(baseDir, aliasPath)
            }
            
            if (!resolvedPath.exists()) {
                errors.add("PathAlias '$pathAlias' points to non-existent path: $resolvedPath")
            }
        }
        
        return errors
    }
}
