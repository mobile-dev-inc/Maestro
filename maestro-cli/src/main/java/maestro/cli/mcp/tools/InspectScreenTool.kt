package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import kotlinx.coroutines.runBlocking

object InspectScreenTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "inspect_screen",
                description = "Get the current screen's view hierarchy as compact JSON. " +
                    "The payload has two top-level keys: `ui_schema` (one-time abbreviations + per-attribute defaults for this platform) " +
                    "and `elements` (the tree, nested via `c` children). Keys on each element are abbreviated (e.g. `b`=bounds, `txt`=text, " +
                    "`rid`=resource-id, `a11y`=accessibilityText/content-desc, `hint`=hintText, `cls`=class, `val`=value, `scroll`=scrollable, " +
                    "`c`=children). Boolean flags (`clickable`, `checked`, `focused`, `selected`, `enabled`) only appear when non-default per `ui_schema.defaults`. " +
                    "Zero-size and empty-container nodes are filtered out. " +
                    "The abbreviated keys above are NOT valid Maestro selector keys. `tapOn` / `assertVisible` / etc. accept " +
                    "`text`, `id`, `index`, and position matchers (`below`, `above`, `leftOf`, `rightOf`). " +
                    "Map `a11y` to `text:` when authoring selectors; never pass `a11y` / `accessibilityText` as a selector. " +
                    "Always copy `txt` values verbatim from this output; never author them from a screenshot, " +
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
                    // Web sessions don't populate `session.device`; chromium always means web.
                    val device = session.device
                    val platform = when {
                        deviceId == "chromium" -> "web"
                        device != null -> device.platform.name.lowercase()
                        else -> error("Device state unavailable for $deviceId; cannot determine platform for schema")
                    }
                    ViewHierarchyFormatters.extractCompactJsonOutput(viewHierarchy.root, platform)
                }

                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to inspect screen: ${e.message}")),
                    isError = true
                )
            }
        }
    }

}
