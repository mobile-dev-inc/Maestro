package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
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
                description = """Get the view hierarchy of the current screen. Use this to understand screen layout, find elements by text/id, or locate interactive components. Elements include bounds, text, resource IDs, and interaction states (clickable, enabled, checked).

Output formats:
- "csv" (default): element_num,depth,bounds,attributes,parent_num
- "simple" (recommended): JSON with pos [x,y] for tap coordinates, rid for element resourceId, bounds, props

Note: Package filtering (app, exclude_apps) is Android only.""",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to get hierarchy from")
                        }
                        putJsonObject("output_format") {
                            put("type", "string")
                            put("description", "Output format: 'csv' (default) or 'simple' (LLM-optimized JSON with pos coordinates)")
                            put("enum", buildJsonArray {
                                add("csv")
                                add("simple")
                            })
                        }
                        putJsonObject("app") {
                            put("type", "string")
                            put("description", "[Android only] Filter elements by package name (e.g., com.example.myapp). Only elements from matching packages are included.")
                        }
                        putJsonObject("exclude_apps") {
                            put("type", "array")
                            put("description", "[Android only] List of package names to exclude. Default excludes 'com.android.systemui'. If specified, the default is NOT included - add it explicitly if needed.")
                            putJsonObject("items") {
                                put("type", "string")
                            }
                        }
                        putJsonObject("clickable_only") {
                            put("type", "boolean")
                            put("description", "Only show clickable/interactive elements. Useful to reduce output size when you only need tappable elements.")
                        }
                        putJsonObject("with_id") {
                            put("type", "boolean")
                            put("description", "Include element_num, depth, and parent_num in output. Useful for understanding element hierarchy relationships.")
                        }
                        putJsonObject("multi_line") {
                            put("type", "boolean")
                            put("description", "Format output with indentation and newlines for human readability. Default is compact single-line JSON.")
                        }
                    },
                    required = listOf("device_id")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val outputFormat = request.arguments["output_format"]?.jsonPrimitive?.content ?: "csv"
                val appFilter = request.arguments["app"]?.jsonPrimitive?.content
                val excludeAppsJson = request.arguments["exclude_apps"]?.jsonArray
                val clickableOnly = request.arguments["clickable_only"]?.jsonPrimitive?.booleanOrNull ?: false
                val withId = request.arguments["with_id"]?.jsonPrimitive?.booleanOrNull ?: false
                val multiLine = request.arguments["multi_line"]?.jsonPrimitive?.booleanOrNull ?: false

                if (deviceId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("device_id is required")),
                        isError = true
                    )
                }

                // Parse exclude_apps array
                val excludeApps: Set<String> = if (excludeAppsJson != null && excludeAppsJson.isNotEmpty()) {
                    excludeAppsJson.mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
                } else {
                    // Default: exclude com.android.systemui
                    setOf("com.android.systemui")
                }

                val result = sessionManager.newSession(
                    host = null,
                    port = null,
                    driverHostPort = null,
                    deviceId = deviceId,
                    platform = null
                ) { session ->
                    val maestro = session.maestro
                    val viewHierarchy = maestro.viewHierarchy()
                    val tree = viewHierarchy.root

                    when (outputFormat) {
                        "simple" -> {
                            // Get device info for screen dimensions
                            val deviceInfo = maestro.deviceInfo()
                            val screenWidth = deviceInfo.widthPixels
                            val screenHeight = deviceInfo.heightPixels

                            ViewHierarchyFormatters.extractLLMOutput(
                                node = tree,
                                screenWidth = screenWidth,
                                screenHeight = screenHeight,
                                appFilter = appFilter,
                                excludeApps = excludeApps,
                                clickableOnly = clickableOnly,
                                prettyPrint = multiLine,
                                withId = withId
                            )
                        }
                        else -> {
                            // CSV format (original format for compatibility)
                            ViewHierarchyFormatters.extractCsvOutput(tree)
                        }
                    }
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
