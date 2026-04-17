package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
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
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to tap on")
                        }
                        putJsonObject("text") {
                            put("type", "string")
                            put("description", "Regex matching the element text (anchored/full-match; use '.*foo.*' for partial). Often copied from the 'text' field of inspect_view_hierarchy output, but might also be 'accessibilityText' or 'hintText'")
                        }
                        putJsonObject("id") {
                            put("type", "string")
                            put("description", "Regex matching the element ID (anchored/full-match; use '.*foo.*' for partial). Typically copied from the 'id' field of inspect_view_hierarchy output.")
                        }
                        putJsonObject("index") {
                            put("type", "integer")
                            put("description", "0-based index if multiple elements match the same criteria")
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
                val deviceId = request.arguments?.get("device_id")?.jsonPrimitive?.content
                val text = request.arguments?.get("text")?.jsonPrimitive?.content
                val id = request.arguments?.get("id")?.jsonPrimitive?.content
                val index = request.arguments?.get("index")?.jsonPrimitive?.intOrNull
                val enabled = request.arguments?.get("enabled")?.jsonPrimitive?.booleanOrNull
                val checked = request.arguments?.get("checked")?.jsonPrimitive?.booleanOrNull
                val focused = request.arguments?.get("focused")?.jsonPrimitive?.booleanOrNull
                val selected = request.arguments?.get("selected")?.jsonPrimitive?.booleanOrNull
                
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
                    platform = null,
                    closeOnCompletion = false
                ) { session ->
                    val elementSelector = ElementSelector(
                        textRegex = text,
                        idRegex = id,
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
                        orchestra.runFlow(listOf(MaestroCommand(command = command)))
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