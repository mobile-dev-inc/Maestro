package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Paths

object RunFlowFilesTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "run_flow_files",
                description = "Run one or more full Maestro test files. If no device is running, you'll need to start a device first.",
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
                    },
                    required = listOf("device_id", "flow_files")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val flowFilesString = request.arguments["flow_files"]?.jsonPrimitive?.content
                
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
                
                // Validate all files exist before executing
                val missingFiles = flowFiles.filter { !File(it).exists() }
                if (missingFiles.isNotEmpty()) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("Files not found: ${missingFiles.joinToString(", ")}")),
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
                    
                    for (flowFile in flowFiles) {
                        try {
                            val commands = YamlCommandReader.readCommands(Paths.get(flowFile))
                            
                            runBlocking {
                                orchestra.executeCommands(commands)
                            }
                            
                            results.add(mapOf(
                                "file" to flowFile,
                                "success" to true,
                                "commands_executed" to commands.size,
                                "message" to "Flow executed successfully"
                            ))
                            totalCommands += commands.size
                            
                        } catch (e: Exception) {
                            results.add(mapOf(
                                "file" to flowFile,
                                "success" to false,
                                "error" to (e.message ?: "Unknown error"),
                                "message" to "Flow execution failed"
                            ))
                        }
                    }
                    
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
                        put("message", if (results.all { (it["success"] as Boolean) }) 
                            "All flows executed successfully" 
                        else 
                            "Some flows failed to execute")
                    }.toString()
                }
                
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to run flow files: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}