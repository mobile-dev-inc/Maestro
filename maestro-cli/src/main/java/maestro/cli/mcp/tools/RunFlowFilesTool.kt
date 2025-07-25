package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import maestro.cli.mcp.MaestroTool
import maestro.cli.mcp.schema.McpToolInput
import maestro.cli.session.MaestroSessionManager
import maestro.cli.util.WorkingDirectory
import maestro.orchestra.Orchestra
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.orchestra.yaml.YamlCommandReader
import java.io.File
import java.nio.file.Paths

// Schema definitions for this tool
@Serializable
data class RunFlowFilesInput(
    val deviceId: String,
    val flowFiles: String, // Comma-separated file paths
    val env: Map<String, String>? = null
) : McpToolInput

@Serializable
data class FlowResult(
    val file: String,
    val success: Boolean,
    val commandsExecuted: Int? = null,
    val error: String? = null,
    val message: String
)

@Serializable
data class RunFlowFilesOutput(
    val success: Boolean,
    val deviceId: String,
    val totalFiles: Int,
    val totalCommandsExecuted: Int,
    val results: List<FlowResult>,
    val envVars: Map<String, String>? = null,
    val message: String
)

object RunFlowFilesTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return MaestroTool.create<RunFlowFilesInput, RunFlowFilesOutput>(
            name = "run_flow_files",
            description = "Run one or more full Maestro test files. If no device is running, you'll need to start a device first. If the command fails using a relative path, try using an absolute path."
        ) { input ->
            val flowFiles = input.flowFiles.split(",").map { it.trim() }
            
            if (flowFiles.isEmpty()) {
                throw Exception("At least one flow file must be provided")
            }
            
            val env = input.env ?: emptyMap()
            
            // Resolve all flow files to File objects once
            val resolvedFiles = flowFiles.map { WorkingDirectory.resolve(it) }
            // Validate all files exist before executing
            val missingFiles = resolvedFiles.filter { !it.exists() }
            if (missingFiles.isNotEmpty()) {
                throw Exception("Files not found: ${missingFiles.joinToString(", ") { it.absolutePath }}")
            }
            
            sessionManager.newSession(
                host = null,
                port = null,
                driverHostPort = null,
                deviceId = input.deviceId,
                platform = null
            ) { session ->
                val orchestra = Orchestra(session.maestro)
                val results = mutableListOf<FlowResult>()
                var totalCommands = 0
                
                for (fileObj in resolvedFiles) {
                    try {
                        val commands = YamlCommandReader.readCommands(fileObj.toPath())
                        val finalEnv = env
                            .withInjectedShellEnvVars()
                            .withDefaultEnvVars(fileObj)
                        val commandsWithEnv = commands.withEnv(finalEnv)
                        
                        runBlocking {
                            orchestra.runFlow(commandsWithEnv)
                        }
                        results.add(FlowResult(
                            file = fileObj.absolutePath,
                            success = true,
                            commandsExecuted = commands.size,
                            message = "Flow executed successfully"
                        ))
                        totalCommands += commands.size
                    } catch (e: Exception) {
                        results.add(FlowResult(
                            file = fileObj.absolutePath,
                            success = false,
                            error = e.message ?: "Unknown error",
                            message = "Flow execution failed"
                        ))
                    }
                }
                
                val finalEnv = env
                    .withInjectedShellEnvVars()
                    .withDefaultEnvVars()
                
                RunFlowFilesOutput(
                    success = results.all { it.success },
                    deviceId = input.deviceId,
                    totalFiles = flowFiles.size,
                    totalCommandsExecuted = totalCommands,
                    results = results,
                    envVars = if (finalEnv.isNotEmpty()) finalEnv else null,
                    message = if (results.all { it.success }) 
                        "All flows executed successfully" 
                    else 
                        "Some flows failed to execute"
                )
            }
        }
    }
}