package maestro.cli.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import maestro.cli.session.MaestroSessionManager
import maestro.debuglog.LogConfig
import maestro.cli.mcp.tools.ListDevicesTool
import maestro.cli.mcp.tools.TakeScreenshotTool
import maestro.cli.mcp.tools.RunTool
import maestro.cli.mcp.tools.InspectViewHierarchyTool
import maestro.cli.mcp.tools.CheatSheetTool
import maestro.cli.mcp.tools.RunOnCloudTool
import maestro.cli.mcp.tools.GetCloudRunStatusTool
import maestro.cli.util.WorkingDirectory

// Main function to run the Maestro MCP server
fun runMaestroMcpServer() {
    // Disable all console logging to prevent interference with JSON-RPC communication
    LogConfig.configure(logFileName = null, printToConsole = false)
    
    val sessionManager = MaestroSessionManager

    // Create the MCP Server instance with Maestro implementation
    val server = Server(
        serverInfo = Implementation(
            name = "maestro",
            version = "1.0.0"
        ),
        options = ServerOptions(
            capabilities = ServerCapabilities(
                tools = ServerCapabilities.Tools(listChanged = true)
            )
        )
    )

    // Register tools
    server.addTools(listOf(
        ListDevicesTool.create(),
        TakeScreenshotTool.create(sessionManager),
        RunTool.create(sessionManager),
        InspectViewHierarchyTool.create(sessionManager),
        CheatSheetTool.create(),
        RunOnCloudTool.create(),
        GetCloudRunStatusTool.create()
    ))


    // Create a transport using standard IO for server communication
    val transport = StdioServerTransport(
        System.`in`.asSource().buffered(),
        System.out.asSink().buffered()
    )

    System.err.println("MCP Server: Started. Waiting for messages. Working directory: ${WorkingDirectory.baseDir}")

    runBlocking {
        val session = server.createSession(transport)
        val done = Job()
        session.onClose { done.complete() }
        done.join()
    }
}