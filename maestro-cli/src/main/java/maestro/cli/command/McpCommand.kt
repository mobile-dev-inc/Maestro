package maestro.cli.command

import picocli.CommandLine
import java.util.concurrent.Callable
import maestro.cli.mcp.runMaestroMcpServer
import maestro.cli.mcp.visualizer.McpVisualizerServer
import java.io.File
import maestro.cli.util.WorkingDirectory

@CommandLine.Command(
    name = "mcp",
    description = [
        "Starts the Maestro MCP server, exposing Maestro device and automation commands as Model Context Protocol (MCP) tools over STDIO for LLM agents and automation clients."
    ],
)
class McpCommand : Callable<Int> {
    @CommandLine.Option(
        names = ["--working-dir"],
        description = ["Base working directory for resolving files"]
    )
    private var workingDir: File? = null

    @CommandLine.Option(
        names = ["--no-visualizer"],
        description = ["Do not start the Maestro MCP visualizer HTTP server."]
    )
    private var noVisualizer: Boolean = false

    @CommandLine.Option(
        names = ["--visualizer-port"],
        description = ["Port for the Maestro MCP visualizer HTTP server. Defaults to a free local port."]
    )
    private var visualizerPort: Int? = null

    override fun call(): Int {
        if (workingDir != null) {
            WorkingDirectory.baseDir = workingDir!!.absoluteFile
        }

        val visualizer = if (noVisualizer) {
            null
        } else {
            McpVisualizerServer.start(visualizerPort)
        }

        try {
            runMaestroMcpServer(visualizerUrl = visualizer?.let { "http://127.0.0.1:${it.port}/" })
        } finally {
            visualizer?.close()
        }
        return 0
    }
}
