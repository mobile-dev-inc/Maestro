package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import maestro.cli.mcp.MaestroTool
import maestro.cli.mcp.schema.McpToolInput
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.orchestra.util.Env.withDefaultEnvVars
import java.nio.file.Files

// Schema definitions for this tool
@Serializable
data class FlowExecutionInput(
    val deviceId: String,
    val flowYaml: String,
    val env: Map<String, String>? = null
) : McpToolInput

@Serializable
data class FlowExecutionOutput(
    val success: Boolean,
    val deviceId: String,
    val commandsExecuted: Int,
    val message: String,
    val envVars: Map<String, String>? = null
)

object RunFlowTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return MaestroTool.create<FlowExecutionInput, FlowExecutionOutput>(
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
            """.trimIndent()
        ) { input ->
            val (commandsExecuted, finalEnv) = sessionManager.newSession(
                host = null,
                port = null,
                driverHostPort = null,
                deviceId = input.deviceId,
                platform = null
            ) { session ->
                // Create a temporary file with the YAML content
                val tempFile = Files.createTempFile("maestro-flow", ".yaml").toFile()
                try {
                    tempFile.writeText(input.flowYaml)

                    // Parse and execute the flow with environment variables
                    val commands = YamlCommandReader.readCommands(tempFile.toPath())
                    val finalEnv = (input.env ?: emptyMap())
                        .withInjectedShellEnvVars()
                        .withDefaultEnvVars(tempFile)
                    val commandsWithEnv = commands.withEnv(finalEnv)

                    val orchestra = Orchestra(session.maestro)

                    runBlocking {
                        orchestra.runFlow(commandsWithEnv)
                    }

                    Pair(commands.size, finalEnv)
                } finally {
                    // Clean up the temporary file
                    tempFile.delete()
                }
            }

            FlowExecutionOutput(
                success = true,
                deviceId = input.deviceId,
                commandsExecuted = commandsExecuted,
                message = "Flow executed successfully",
                envVars = if (finalEnv.isNotEmpty()) finalEnv else null
            )
        }
    }
}