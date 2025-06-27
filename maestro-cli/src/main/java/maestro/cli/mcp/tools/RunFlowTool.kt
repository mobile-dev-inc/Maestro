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
                description = """
                    Use this when interacting with a device and running adhoc commands, preferably one at a time.

                    Whenever you're exploring an app, testing out commands or debugging, prefer using this tool over creating temp files and using run_flow_files.

                    Run a set of Maestro commands (one or more). This can be a full maestro script (including headers), a set of commands (one per line) or simply a single command (eg '- tapOn: 123').

                    If this fails due to no device running, please ask the user to start a device!

                    If you don't have an up-to-date view hierarchy or screenshot on which to execute the commands, please call inspect_view_hierarchy first, instead of blindly guessing.

                    *** You don't need to call check_syntax before executing this, as syntax will be checked as part of the execution flow. ***

                    Use the `inspect_view_hierarchy` tool to retrieve the current view hierarchy and use it to execute commands on the device.
                    Use the `cheat_sheet` tool to retrieve a summary of Maestro's flow syntax before using any of the other tools.

                    Examples of valid inputs:
                    ```
                    - tapOn: 123
                    ```

                    ```
                    appId: any
                    ---
                    - tapOn: 123
                    ```

                    ```
                    appId: any
                    # other headers here
                    ---
                    - tapOn: 456
                    - scroll
                    # other commands here
                    ```
                """.trimIndent(),
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