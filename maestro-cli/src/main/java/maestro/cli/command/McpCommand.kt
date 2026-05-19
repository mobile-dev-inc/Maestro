package maestro.cli.command

import picocli.CommandLine
import java.util.concurrent.Callable
import maestro.cli.mcp.runMaestroMcpServer
import maestro.cli.mcp.viewer.McpViewerServer
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
        names = ["--no-viewer"],
        description = ["Do not start the Maestro Viewer HTTP server."]
    )
    private var noViewer: Boolean = false

    @CommandLine.Option(
        names = ["--viewer-port"],
        description = ["Port for the Maestro Viewer HTTP server. Defaults to a free local port."]
    )
    private var viewerPort: Int? = null

    override fun call(): Int {
        if (workingDir != null) {
            WorkingDirectory.baseDir = workingDir!!.absoluteFile
        }

        val viewer = if (noViewer) {
            null
        } else {
            McpViewerServer.start(viewerPort)
        }

        try {
            runMaestroMcpServer(viewerUrl = viewer?.let { "http://127.0.0.1:${it.port}/" })
        } finally {
            viewer?.close()
        }
        return 0
    }
}
