package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.Maestro

object InspectViewHierarchyTool {
    fun create(maestro: Maestro): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "inspect_view_hierarchy",
                description = "Get the nested view hierarchy of the current screen in CSV format. Returns UI elements " +
                    "with bounds coordinates for interaction. Use this to understand screen layout, find specific elements " +
                    "by text/id, or locate interactive components. Elements include bounds (x,y,width,height), text content, " +
                    "resource IDs, and interaction states (clickable, enabled, checked).",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {},
                    required = emptyList()
                )
            )
        ) { request ->
            try {
                val viewHierarchy = maestro.viewHierarchy()
                val tree = viewHierarchy.root
                val result = ViewHierarchyFormatters.extractCsvOutput(tree)

                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to inspect UI: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}
