package maestro.cli.command

import picocli.CommandLine
import java.util.concurrent.Callable
import maestro.cli.mcp.McpServer

@CommandLine.Command(
    name = "mcp",
    description = [
        "Starts the Maestro MCP server, exposing Maestro device and automation commands as Model Context Protocol (MCP) tools over STDIO for LLM agents and automation clients."
    ],
)
class McpCommand : Callable<Int> {
    override fun call(): Int {
        McpServer.start()
        return 0
    }
} 