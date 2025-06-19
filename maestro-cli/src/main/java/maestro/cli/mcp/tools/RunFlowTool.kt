package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files

object RunFlowTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "run_flow",
                description = "Use this when interacting with a device and running adhoc commands, preferably one at a time. " +
                    "Explore an app, test commands, or debug by running Maestro commands. " +
                    "Call list_devices first if you don't have an up-to-date view of available devices. " +
                    "Syntax will be automatically checked during execution. " +
                    "Example formats: '- tapOn: 123' or 'appId: com.example\\n---\\n- tapOn: 123'",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to run the flow on")
                        }
                        putJsonObject("flow_yaml") {
                            put("type", "string")
                            put("description", "YAML-formatted Maestro flow content to execute")
                        }
                    },
                    required = listOf("device_id", "flow_yaml")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val flowYaml = request.arguments["flow_yaml"]?.jsonPrimitive?.content
                
                if (deviceId == null || flowYaml == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("Both device_id and flow_yaml are required")),
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
                    // Create a temporary file with the YAML content
                    val tempFile = Files.createTempFile("maestro-flow", ".yaml").toFile()
                    try {
                        tempFile.writeText(flowYaml)
                        
                        // Parse and execute the flow
                        val commands = YamlCommandReader.readCommands(tempFile.toPath())
                        val orchestra = Orchestra(session.maestro)
                        
                        runBlocking {
                            orchestra.executeCommands(commands)
                        }
                        
                        buildJsonObject {
                            put("success", true)
                            put("device_id", deviceId)
                            put("commands_executed", commands.size)
                            put("message", "Flow executed successfully")
                        }.toString()
                    } finally {
                        // Clean up the temporary file
                        tempFile.delete()
                    }
                }
                
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to run flow: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}