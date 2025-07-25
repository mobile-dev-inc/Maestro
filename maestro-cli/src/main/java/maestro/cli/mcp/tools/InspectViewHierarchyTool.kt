package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import maestro.TreeNode
import maestro.cli.mcp.MaestroTool
import maestro.cli.mcp.schema.McpToolInput
import maestro.cli.session.MaestroSessionManager

// Schema definitions for this tool
@Serializable
data class InspectViewHierarchyInput(
    val deviceId: String
) : McpToolInput

@Serializable
data class InspectViewHierarchyOutput(
    val success: Boolean,
    val deviceId: String,
    val hierarchy: String, // CSV format view hierarchy
    val format: String = "csv"
)

object InspectViewHierarchyTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return MaestroTool.create<InspectViewHierarchyInput, InspectViewHierarchyOutput>(
            name = "inspect_view_hierarchy",
            description = "Get the nested view hierarchy of the current screen in CSV format. Returns UI elements " +
                "with bounds coordinates for interaction. Use this to understand screen layout, find specific elements " +
                "by text/id, or locate interactive components. Elements include bounds (x,y,width,height), text content, " +
                "resource IDs, and interaction states (clickable, enabled, checked)."
        ) { input ->
            val hierarchy = sessionManager.newSession(
                host = null,
                port = null,
                driverHostPort = null,
                deviceId = input.deviceId,
                platform = null
            ) { session ->
                val maestro = session.maestro
                val viewHierarchy = maestro.viewHierarchy()
                val tree = viewHierarchy.root
                
                // Return CSV format (original format for compatibility)
                ViewHierarchyFormatters.extractCsvOutput(tree)
            }
            
            InspectViewHierarchyOutput(
                success = true,
                deviceId = input.deviceId,
                hierarchy = hierarchy
            )
        }
    }
    
}