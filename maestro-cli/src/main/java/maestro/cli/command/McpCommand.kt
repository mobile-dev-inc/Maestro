package maestro.cli.command

import picocli.CommandLine
import java.util.concurrent.Callable
import maestro.cli.mcp.runMaestroMcpServer

@CommandLine.Command(
    name = "mcp",
    description = [
        "Starts the Maestro MCP server, exposing Maestro device and automation commands as Model Context Protocol (MCP) tools over STDIO for LLM agents and automation clients."
    ],
)
class McpCommand : Callable<Int> {
    
    @CommandLine.Option(
        names = ["--port"],
        defaultValue = "7200",
        description = ["Port for MCP server to use (default: 7200)"]
    )
    private var port: Int = 7200

    override fun call(): Int {
        runMaestroMcpServer(port)
        return 0
    }
} 