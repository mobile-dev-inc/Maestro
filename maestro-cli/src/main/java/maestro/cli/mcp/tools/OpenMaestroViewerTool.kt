package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.buildJsonObject

object OpenMaestroViewerTool {
    fun create(viewerUrl: String?): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "open_maestro_viewer",
                description = "Returns the Maestro Viewer URL so it can be shared with the user. " +
                    "Maestro Viewer shows live device state, the current flow's commands, and tool activity. " +
                    "In Claude Code desktop, the returned URL can be dropped into a temporary `launch.json` " +
                    "preview config to render Maestro Viewer inside Claude's embedded preview pane.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList()
                )
            )
        ) { _ ->
            if (viewerUrl == null) {
                return@RegisteredTool CallToolResult(
                    content = listOf(TextContent(
                        "Maestro Viewer is not running. Restart the MCP server without --no-viewer to enable it."
                    )),
                    isError = true
                )
            }
            CallToolResult(content = listOf(TextContent(
                "Maestro Viewer is available at $viewerUrl." +
                    " Surface this URL to the user and let them open it themselves — do not shell out to" +
                    " `open`, `xdg-open`, or any other launcher. Most environments running this MCP server" +
                    " (Claude Code, Cursor, Codex, etc.) have an embedded browser or preview pane the user" +
                    " would rather use, and forcing an external window is disruptive."
            )))
        }
    }
}
