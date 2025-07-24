package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.ElementSelector
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.MaestroCommand
import kotlinx.coroutines.runBlocking

object TapOnTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "tap_on",
                description = "Tap on a UI element by selector or description",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to tap on")
                        }
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "Text content to match (from 'text' field in inspect_ui output)")
                        }
                        putJsonObject("id") {
                            put("type", "string")
                            put("description", "Element ID to match (from 'id' field in inspect_ui output)")
                        }
                        putJsonObject("index") {
                            put("type", "integer")
                            put("description", "0-based index if multiple elements match the same criteria")
                        }
                        putJsonObject("use_fuzzy_matching") {
                            put("type", "boolean")
                            put("description", "Whether to use fuzzy/partial text matching (true, default) or exact regex matching (false)")
                        }
                        putJsonObject("enabled") {
                            put("type", "boolean")
                            put("description", "If true, only match enabled elements. If false, only match disabled elements. Omit this field to match regardless of enabled state.")
                        }
                        putJsonObject("checked") {
                            put("type", "boolean")
                            put("description", "If true, only match checked elements. If false, only match unchecked elements. Omit this field to match regardless of checked state.")
                        }
                        putJsonObject("focused") {
                            put("type", "boolean")
                            put("description", "If true, only match focused elements. If false, only match unfocused elements. Omit this field to match regardless of focus state.")
                        }
                        putJsonObject("selected") {
                            put("type", "boolean")
                            put("description", "If true, only match selected elements. If false, only match unselected elements. Omit this field to match regardless of selection state.")
                        }
                    },
                    required = listOf("device_id")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val text = request.arguments["text"]?.jsonPrimitive?.content
                val id = request.arguments["id"]?.jsonPrimitive?.content
                val index = request.arguments["index"]?.jsonPrimitive?.intOrNull
                val useFuzzyMatching = request.arguments["use_fuzzy_matching"]?.jsonPrimitive?.booleanOrNull ?: true
                val enabled = request.arguments["enabled"]?.jsonPrimitive?.booleanOrNull
                val checked = request.arguments["checked"]?.jsonPrimitive?.booleanOrNull
                val focused = request.arguments["focused"]?.jsonPrimitive?.booleanOrNull
                val selected = request.arguments["selected"]?.jsonPrimitive?.booleanOrNull
                
                if (deviceId == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("device_id is required")),
                        isError = true
                    )
                }
                
                // Validate that at least one selector is provided
                if (text == null && id == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("Either 'text' or 'id' parameter must be provided")),
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
                    // Escape special regex characters to prevent regex injection issues
                    fun escapeRegex(input: String): String {
                        return input.replace(Regex("[()\\[\\]{}+*?^$|.\\\\]")) { "\\${it.value}" }
                    }
                    
                    val elementSelector = ElementSelector(
                        textRegex = if (useFuzzyMatching && text != null) ".*${escapeRegex(text)}.*" else text,
                        idRegex = if (useFuzzyMatching && id != null) ".*${escapeRegex(id)}.*" else id,
                        index = index?.toString(),
                        enabled = enabled,
                        checked = checked,
                        focused = focused,
                        selected = selected
                    )
                    
                    val command = TapOnElementCommand(
                        selector = elementSelector,
                        retryIfNoChange = true,
                        waitUntilVisible = true
                    )
                    
                    val orchestra = Orchestra(session.maestro)
                    runBlocking {
                        orchestra.executeCommands(listOf(MaestroCommand(command = command)))
                    }
                    
                    buildJsonObject {
                        put("success", true)
                        put("device_id", deviceId)
                        put("message", "Tap executed successfully")
                    }.toString()
                }
                
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to tap element: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}