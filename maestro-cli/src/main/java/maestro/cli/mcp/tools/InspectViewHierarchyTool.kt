package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.TreeNode
import kotlinx.coroutines.runBlocking

object InspectViewHierarchyTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "inspect_view_hierarchy",
                description = "Get the nested view hierarchy of the current screen in CSV format. Returns UI elements " +
                    "with bounds coordinates for interaction. Use this to understand screen layout, find specific elements " +
                    "by text/id, or locate interactive components. Columns: `element_num,depth,bounds,attributes,parent_num`; " +
                    "the `attributes` cell holds semicolon-separated `key=value` pairs (e.g. `text=Submit; accessibilityText=Submit button; resource_id=btn_submit`). " +
                    "Those attribute keys are NOT valid Maestro selector keys. `tapOn` / `assertVisible` / etc. accept " +
                    "`text`, `id`, `index`, and position matchers (`below`, `above`, `leftOf`, `rightOf`). " +
                    "Map `accessibilityText=Foo` to `text: Foo`; never pass `accessibilityText` as a selector. " +
                    "Always copy `text:` values verbatim from this output; never author them from a screenshot, " +
                    "which is a common source of hallucinated strings (e.g. an element showing a heart icon " +
                    "looks like a \"Favorite\" button in a screenshot but has no such text in the hierarchy). " +
                    "Maestro's `text:` matcher is full-string regex with IGNORE_CASE, so a partial string does " +
                    "NOT match: `text: \"RNR 352\"` will miss an element whose real text is " +
                    "`\"RNR 352 - Expo Launch with Cedric van Putten\"`. Use the full on-screen string, or " +
                    "anchor with a regex like `\"RNR 352.*\"`.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to get hierarchy from")
                        }
                    },
                    required = listOf("device_id")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments?.get("device_id")?.jsonPrimitive?.content
                
                if (deviceId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("device_id is required")),
                        isError = true
                    )
                }
                
                val result = sessionManager.newSession(
                    host = null,
                    port = null,
                    driverHostPort = null,
                    deviceId = deviceId,
                    platform = null
                ) { session ->
                    val maestro = session.maestro
                    val viewHierarchy = runBlocking { maestro.viewHierarchy() }
                    val tree = viewHierarchy.root
                    
                    // Return CSV format (original format for compatibility)
                    ViewHierarchyFormatters.extractCsvOutput(tree)
                }
                
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