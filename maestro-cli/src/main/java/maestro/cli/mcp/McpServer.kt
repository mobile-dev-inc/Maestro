package maestro.cli.mcp

import io.ktor.utils.io.streams.*
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlinx.io.*
import maestro.cli.session.MaestroSessionManager
import maestro.debuglog.LogConfig
import maestro.cli.mcp.tools.ListDevicesTool
import maestro.cli.mcp.tools.StartDeviceTool
import maestro.cli.mcp.tools.LaunchAppTool
import maestro.cli.mcp.tools.TakeScreenshotTool
import maestro.cli.mcp.tools.TapOnTool
import maestro.cli.mcp.tools.InputTextTool
import maestro.cli.mcp.tools.BackTool
import maestro.cli.mcp.tools.StopAppTool
import maestro.cli.mcp.tools.RunFlowTool
import maestro.cli.mcp.tools.RunFlowFilesTool
import maestro.cli.mcp.tools.CheckFlowSyntaxTool
import maestro.cli.mcp.tools.InspectViewHierarchyTool
import maestro.cli.mcp.tools.CheatSheetTool
import maestro.cli.mcp.tools.QueryDocsTool

// Main function to run the Maestro MCP server
fun runMaestroMcpServer() {
    // Disable all console logging to prevent interference with JSON-RPC communication
    LogConfig.configure(logFileName = null, printToConsole = false)
    
    val sessionManager = MaestroSessionManager

    // Create the MCP Server instance with Maestro implementation
    val server = Server(
        Implementation(
            name = "maestro",
            version = "1.0.0"
        ),
        ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    )

    // Register tools
    server.addTools(listOf(
        ListDevicesTool.create(),
        StartDeviceTool.create(),
        LaunchAppTool.create(sessionManager),
        TakeScreenshotTool.create(sessionManager),
        TapOnTool.create(sessionManager),
        InputTextTool.create(sessionManager),
        BackTool.create(sessionManager),
        StopAppTool.create(sessionManager),
        RunFlowTool.create(sessionManager),
        RunFlowFilesTool.create(sessionManager),
        CheckFlowSyntaxTool.create(),
        InspectViewHierarchyTool.create(sessionManager),
        CheatSheetTool.create(),
        QueryDocsTool.create()
    ))


    // Create a transport using standard IO for server communication
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered()
    )

    System.err.println("Server: Started. Waiting for messages.")

    runBlocking {
        server.connect(transport)
        val done = Job()
        server.onClose {
            done.complete()
        }
        done.join()
    }
}