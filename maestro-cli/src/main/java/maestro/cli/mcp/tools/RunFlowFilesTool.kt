package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.orchestra.util.Env.withDefaultEnvVars
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Paths
import maestro.cli.util.WorkingDirectory

object RunFlowFilesTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "run_flow_files",
                description = "Run one or more full Maestro test files. If no device is running, you'll need to start a device first. If the command fails using a relative path, try using an absolute path.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to run the flows on")
                        }
                        putJsonObject("flow_files") {
                            put("type", "string")
                            put("description", "Comma-separated file paths to YAML flow files to execute (e.g., 'flow1.yaml,flow2.yaml')")
                        }
                        putJsonObject("env") {
                            put("type", "object")
                            put("description", "Optional environment variables to inject into the flows (e.g., {\"APP_ID\": \"com.example.app\", \"LANGUAGE\": \"tr\", \"COUNTRY\": \"TR\"})")
                            putJsonObject("additionalProperties") {
                                put("type", "string")
                            }
                        }
                    },
                    required = listOf("device_id", "flow_files")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val flowFilesString = request.arguments["flow_files"]?.jsonPrimitive?.content
                val envParam = request.arguments["env"]?.jsonObject
                
                if (deviceId == null || flowFilesString == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("Both device_id and flow_files are required")),
                        isError = true
                    )
                }
                
                val flowFiles = flowFilesString.split(",").map { it.trim() }
                
                if (flowFiles.isEmpty()) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("At least one flow file must be provided")),
                        isError = true
                    )
                }
                
                // Parse environment variables from JSON object
                val env = envParam?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                
                // Resolve all flow files to File objects once
                val resolvedFiles = flowFiles.map { WorkingDirectory.resolve(it) }
                // Validate all files exist before executing
                val missingFiles = resolvedFiles.filter { !it.exists() }
                if (missingFiles.isNotEmpty()) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("Files not found: ${missingFiles.joinToString(", ") { it.absolutePath }}")),
                        isError = true
                    )
                }
                
                val result = sessionManager.newSession(
                    host = null,
                    port = null,
                    driverHostPort = null,
                    deviceId = deviceId,
                    platform = null
                ) { session ->
                    val orchestra = Orchestra(session.maestro)
                    val results = mutableListOf<Map<String, Any>>()
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
                            results.add(mapOf(
                                "file" to fileObj.absolutePath,
                                "success" to true,
                                "commands_executed" to commands.size,
                                "message" to "Flow executed successfully"
                            ))
                            totalCommands += commands.size
                        } catch (e: Exception) {
                            results.add(mapOf(
                                "file" to fileObj.absolutePath,
                                "success" to false,
                                "error" to (e.message ?: "Unknown error"),
                                "message" to "Flow execution failed"
                            ))
                        }
                    }
                    
                    val finalEnv = env
                        .withInjectedShellEnvVars()
                        .withDefaultEnvVars()
                    
                    buildJsonObject {
                        put("success", results.all { (it["success"] as Boolean) })
                        put("device_id", deviceId)
                        put("total_files", flowFiles.size)
                        put("total_commands_executed", totalCommands)
                        putJsonArray("results") {
                            results.forEach { result ->
                                addJsonObject {
                                    result.forEach { (key, value) ->
                                        when (value) {
                                            is String -> put(key, value)
                                            is Boolean -> put(key, value)
                                            is Int -> put(key, value)
                                            else -> put(key, value.toString())
                                        }
                                    }
                                }
                            }
                        }
                        if (finalEnv.isNotEmpty()) {
                            putJsonObject("env_vars") {
                                finalEnv.forEach { (key, value) ->
                                    put(key, value)
                                }
                            }
                        }
                        put("message", if (results.all { (it["success"] as Boolean) }) 
                            "All flows executed successfully" 
                        else 
                            "Some flows failed to execute")
                    }.toString()
                }
                
                // Check if any flows failed and return isError accordingly
                val anyFlowsFailed = result.contains("\"success\":false")                
                CallToolResult(
                    content = listOf(TextContent(result)),
                    isError = anyFlowsFailed
                )
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to run flow files: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}