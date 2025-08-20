package maestro.cli.util

import maestro.orchestra.yaml.MaestroFlowParser
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

object DependencyResolver {

    fun discoverAllDependencies(flowFile: Path): List<Path> {
        val discoveredFiles = mutableSetOf<Path>()
        val filesToProcess = mutableListOf(flowFile)
        
        while (filesToProcess.isNotEmpty()) {
            val currentFile = filesToProcess.removeFirst()
            
            // Skip if we've already processed this file (prevents circular references)
            if (discoveredFiles.contains(currentFile)) continue
            
            // Add current file to discovered set
            discoveredFiles.add(currentFile)
            
            try {
                // Only process YAML files for dependency discovery
                if (!isYamlFile(currentFile)) {
                    continue
                }
                
                val flowContent = Files.readString(currentFile)
                val commands = MaestroFlowParser.parseFlow(currentFile, flowContent)
                
                // Discover dependencies from each command
                val dependencies = commands.flatMap { maestroCommand ->
                    val commandDependencies = mutableListOf<Path>()

                    // Check for runFlow commands and add the dependency if it exists (sourceDescription is not null)
                    maestroCommand.runFlowCommand?.let { runFlow ->
                        resolveDependencyFile(currentFile, runFlow.sourceDescription)?.let { commandDependencies.add(it) }
                    }
                    
                    // Check for runScript commands and add the dependency if it exists (sourceDescription is not null)
                    maestroCommand.runScriptCommand?.let { runScript ->
                        resolveDependencyFile(currentFile, runScript.sourceDescription)?.let { commandDependencies.add(it) }
                    }
                    
                    // Check for addMedia commands and add the dependency if it exists (mediaPaths is not null)
                    maestroCommand.addMediaCommand?.let { addMedia ->
                        addMedia.mediaPaths.forEach { mediaPath ->
                            resolveDependencyFile(currentFile, mediaPath)?.let { commandDependencies.add(it) }
                        }
                    }
                    
                    commandDependencies
                }
                
                val newDependencies = dependencies.filter { it.exists() && !discoveredFiles.contains(it) }
                filesToProcess.addAll(newDependencies)
                
            } catch (e: Exception) {
                // Ignore
            }
        }
        
        return discoveredFiles.toList()
    }
    
    private fun resolvePath(flowPath: Path, requestedPath: String): Path {
        val path = flowPath.fileSystem.getPath(requestedPath)
        
        return if (path.isAbsolute) {
            path
        } else {
            flowPath.resolveSibling(path).toAbsolutePath()
        }
    }

    private fun isYamlFile(path: Path): Boolean {
        val filename = path.fileName.toString().lowercase()
        return filename.endsWith(".yaml") || filename.endsWith(".yml")
    }

    private fun isJsFile(path: Path): Boolean {
        val filename = path.fileName.toString().lowercase()
        return filename.endsWith(".js")
    }

    private fun resolveDependencyFile(currentFile: Path, requestedPath: String?): Path? {
        val trimmed = requestedPath?.trim()
        if (trimmed.isNullOrEmpty()) return null
        val resolved = resolvePath(currentFile, trimmed)
        return if (resolved.exists() && !Files.isDirectory(resolved)) resolved else null
    }

    fun getDependencySummary(flowFile: Path): String {
        val dependencies = discoverAllDependencies(flowFile)
        val mainFile = dependencies.firstOrNull { it == flowFile }
        val subflows = dependencies.filter { it != flowFile && isYamlFile(it) }
        val scripts = dependencies.filter { it != flowFile && isJsFile(it) }
        val otherFiles = dependencies.filter { it != flowFile && !isYamlFile(it) && !isJsFile(it) }
        
        return buildString {
            appendLine("Dependency discovery for: ${flowFile.fileName}")
            appendLine("Total files: ${dependencies.size}")
            if (subflows.isNotEmpty()) appendLine("Subflows: ${subflows.size}")
            if (scripts.isNotEmpty()) appendLine("Scripts: ${scripts.size}")
            if (otherFiles.isNotEmpty()) appendLine("Other files: ${otherFiles.size}")
            
            if (subflows.isNotEmpty()) {
                appendLine("Subflow files:")
                subflows.forEach { appendLine("  - ${it.fileName}") }
            }
            if (scripts.isNotEmpty()) {
                appendLine("Script files:")
                scripts.forEach { appendLine("  - ${it.fileName}") }
            }
            if (otherFiles.isNotEmpty()) {
                appendLine("Other files:")
                otherFiles.forEach { appendLine("  - ${it.fileName}") }
            }
        }
    }
}
