package maestro.cli.util

import maestro.orchestra.yaml.YamlCommandReader
import maestro.orchestra.yaml.YamlFluentCommand
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
                if (!currentFile.fileName.toString().endsWith(".yaml") && !currentFile.fileName.toString().endsWith(".yml")) {
                    continue
                }
                
                val flowContent = Files.readString(currentFile)
                val commands = MaestroFlowParser.parseFlow(currentFile, flowContent)
                
                // Discover dependencies from each command
                val dependencies = commands.flatMap { maestroCommand ->
                    val commandDependencies = mutableListOf<Path>()

                    // Check for runFlow commands
                    maestroCommand.runFlowCommand?.let { runFlow ->
                        val flowPath = resolvePath(currentFile, runFlow.sourceDescription ?: "")
                        if (flowPath.exists()) {
                            commandDependencies.add(flowPath)
                        }
                    }
                    
                    // Check for runScript commands
                    maestroCommand.runScriptCommand?.let { runScript ->
                        val scriptPath = resolvePath(currentFile, runScript.sourceDescription ?: "")
                        if (scriptPath.exists()) {
                            commandDependencies.add(scriptPath)
                        }
                    }
                    
                    // Check for addMedia commands
                    maestroCommand.addMediaCommand?.let { addMedia ->
                        addMedia.mediaPaths.forEach { mediaPath ->
                            val mediaFile = resolvePath(currentFile, mediaPath)
                            if (mediaFile.exists()) {
                                commandDependencies.add(mediaFile)
                            }
                        }
                    }
                    
                    commandDependencies
                }
                
                val newDependencies = dependencies.filter { it.exists() && !discoveredFiles.contains(it) }
                filesToProcess.addAll(newDependencies)
                
            } catch (e: Exception) {
                println("Warning: Could not parse dependencies for ${currentFile.fileName}: ${e.message}")
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

    fun getDependencySummary(flowFile: Path): String {
        val dependencies = discoverAllDependencies(flowFile)
        val mainFile = dependencies.firstOrNull { it == flowFile }
        val subflows = dependencies.filter { it != flowFile && it.fileName.toString().substringAfterLast('.', "") in listOf("yaml", "yml") }
        val scripts = dependencies.filter { it != flowFile && it.fileName.toString().substringAfterLast('.', "") == "js" }
        val otherFiles = dependencies.filter { it != flowFile && it.fileName.toString().substringAfterLast('.', "") !in listOf("yaml", "yml", "js") }
        
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
