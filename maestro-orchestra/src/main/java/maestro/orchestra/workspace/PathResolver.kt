package maestro.orchestra.workspace

import maestro.orchestra.WorkspaceConfig
import java.io.File

object PathResolver {
    
    fun resolveAliases(path: String, workspaceConfig: WorkspaceConfig?): String {
        val paths = workspaceConfig?.paths ?: return path
        
        // Find the first matching alias and replace it
        for ((alias, aliasPath) in paths) {
            if (path.startsWith(alias)) {
                val remainingPath = path.substring(alias.length)
                return "$aliasPath$remainingPath"
            }
        }
        
        return path
    }
    
    fun validateAliasPaths(workspaceConfig: WorkspaceConfig?, baseDir: File): List<String> {
        val errors = mutableListOf<String>()
        
        val aliasMap = workspaceConfig?.paths ?: return errors
        
        // Check for circular references
        for ((alias, aliasPath) in aliasMap) {
            if (aliasPath.startsWith("@") || aliasPath.startsWith("!") || aliasPath.startsWith("~")) {
                errors.add("Alias '$alias' references another alias '$aliasPath'. Circular references are not supported.")
            }
        }
        
        // Check that alias paths exist
        for ((alias, aliasPath) in aliasMap) {
            val resolvedPath = if (aliasPath.startsWith("/") || aliasPath.matches(Regex("^[A-Za-z]:.*"))) {
                // Absolute path
                File(aliasPath)
            } else {
                // Relative path
                File(baseDir, aliasPath)
            }
            
            if (!resolvedPath.exists()) {
                errors.add("Alias '$alias' points to non-existent path: $resolvedPath")
            }
        }
        
        return errors
    }
}
