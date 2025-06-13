package maestro.cli.mcp

import kotlinx.coroutines.runBlocking
import maestro.cli.mcp.tools.ListDevicesTool
import maestro.cli.mcp.tools.LaunchAppTool
import maestro.cli.mcp.tools.TapOnTool
import maestro.cli.mcp.tools.TakeScreenshotTool
import maestro.cli.mcp.tools.StartDeviceTool
import maestro.cli.mcp.tools.InspectUITool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import maestro.cli.session.MaestroSessionManager
import maestro.debuglog.LogConfig

// Add missing imports
import maestro.cli.mcp.Transport
import maestro.cli.mcp.InitializeRequest
import maestro.cli.mcp.InitializeResponse
import maestro.cli.mcp.ToolsListRequest
import maestro.cli.mcp.ToolsListResponse
import maestro.cli.mcp.ToolCallRequest
import maestro.cli.mcp.ToolCallResponse
import maestro.cli.mcp.InitializeResult
import maestro.cli.mcp.ToolsListResult
import maestro.cli.mcp.ServerInfo
import maestro.cli.mcp.ToolRegistry

object McpServer {
    fun start() {
        // Disable all console logging to prevent interference with JSON-RPC communication
        LogConfig.configure(logFileName = null, printToConsole = false)
        
        val sessionManager = MaestroSessionManager

        val toolRegistry = ToolRegistry().apply {
            // Register core tools
            register(ListDevicesTool(sessionManager))
            register(LaunchAppTool(sessionManager))
            register(TapOnTool(sessionManager))
            register(TakeScreenshotTool(sessionManager))
            register(StartDeviceTool())
            register(InspectUITool(sessionManager))
        }

        val transportImpl = Transport(System.`in`, System.out)
        System.err.println("Server: Started. Waiting for messages.")

        runBlocking {
            while (true) {
                val request = transportImpl.read()
                if (request == null) {
                    kotlinx.coroutines.delay(50)
                    continue
                }
                System.err.println("Server: Received request: $request")
                when (request) {
                    is InitializeRequest -> {
                        System.err.println("Server: Handling InitializeRequest.")
                        val capabilities = mapOf(
                            "tools" to JsonObject(mapOf("listChanged" to JsonPrimitive(true))),
                            "logging" to JsonObject(emptyMap()),
                            "prompts" to JsonObject(emptyMap()),
                            "resources" to JsonObject(emptyMap())
                        )
                        val response = InitializeResponse(
                            id = request.id,
                            result = InitializeResult(
                                protocolVersion = request.params.protocolVersion,
                                capabilities = capabilities,
                                serverInfo = ServerInfo(
                                    name = "maestro",
                                    version = "1.0.0"
                                ),
                                instructions = "Welcome to Maestro MCP server."
                            )
                        )
                        transportImpl.writeInitializeResponse(response)
                    }
                    is ToolsListRequest -> {
                        System.err.println("Server: Handling ToolsListRequest.")
                        val response = ToolsListResponse(
                            id = request.id,
                            result = ToolsListResult(
                                tools = toolRegistry.listDefinitions()
                            )
                        )
                        transportImpl.writeToolsListResponse(response)
                    }
                    is ToolCallRequest -> {
                        System.err.println("Server: Handling ToolCallRequest for tool: ${request.params.name}")
                        val result = toolRegistry.execute(request.params.name, request.params.arguments)
                        val response = ToolCallResponse(
                            id = request.id,
                            result = result
                        )
                        transportImpl.writeToolCallResponse(response)
                    }
                }
            }
        }
    }
} 