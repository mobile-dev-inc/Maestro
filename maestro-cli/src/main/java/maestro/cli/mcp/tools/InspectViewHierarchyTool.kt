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
                description = "Get the nested view hierarchy of the current screen. Returns UI elements as a tree structure " +
                    "with bounds coordinates for interaction. Use this to understand screen layout, find specific elements " +
                    "by text/id, or locate interactive components. Elements include bounds (x,y,width,height), text content, " +
                    "resource IDs, and interaction states (clickable, enabled, checked).",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("device_id") {
                            put("type", "string")
                            put("description", "The ID of the device to get hierarchy from")
                        }
                        putJsonObject("interactive_only") {
                            put("type", "boolean")
                            put("description", "If true, only return interactive/automatable elements. If false (default), return all visible elements")
                            put("default", false)
                        }
                    },
                    required = listOf("device_id")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val interactiveOnly = request.arguments["interactive_only"]?.jsonPrimitive?.booleanOrNull ?: false
                
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
                    
                    val viewHierarchy = maestro.viewHierarchy()
                    
                    // Extract elements as nested hierarchy
                    val rootElement = extractElementsHierarchy(viewHierarchy.root, interactiveOnly)
                    
                    buildJsonObject {
                        putJsonArray("elements") {
                            add(rootElement)
                        }
                    }.toString()
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
    
    private data class ElementBounds(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )
    
    
    private fun extractElementsHierarchy(node: TreeNode, interactiveOnly: Boolean): JsonObject {
        val bounds = parseBounds(node.attributes["bounds"])
        
        // Create element from current node
        val element = if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            createElementFromNode(node, bounds, interactiveOnly)
        } else {
            null
        }
        
        // Process children recursively
        val allChildren = mutableListOf<JsonObject>()
        node.children.forEach { child ->
            val childElement = extractElementsHierarchy(child, interactiveOnly)
            if (childElement.isNotEmpty()) {
                allChildren.add(childElement)
            }
        }
        
        // Deduplicate children by bounds, keeping elements with visible properties
        val meaningfulChildren = deduplicateByBounds(allChildren)
        
        // Return element with children if it has meaningful content or meaningful children
        return when {
            element != null && meaningfulChildren.isNotEmpty() -> {
                buildJsonObject {
                    element.forEach { key, value -> put(key, value) }
                    putJsonArray("children") {
                        meaningfulChildren.forEach { add(it) }
                    }
                }
            }
            element != null -> element // Leaf node, no children array
            meaningfulChildren.size == 1 -> {
                // Single child - flatten to avoid unnecessary nesting
                meaningfulChildren.first()
            }
            meaningfulChildren.isNotEmpty() -> {
                // Multiple meaningful children - create container
                buildJsonObject {
                    if (bounds != null) {
                        putJsonObject("bounds") {
                            put("x", bounds.x)
                            put("y", bounds.y)
                            put("width", bounds.width)
                            put("height", bounds.height)
                        }
                    }
                    putJsonArray("children") {
                        meaningfulChildren.forEach { add(it) }
                    }
                }
            }
            else -> buildJsonObject {} // Empty object for filtering
        }
    }
    
    
    private fun deduplicateByBounds(elements: List<JsonObject>): List<JsonObject> {
        return elements
            .groupBy { element ->
                val bounds = element["bounds"]?.jsonObject
                "${bounds?.get("x")},${bounds?.get("y")},${bounds?.get("width")},${bounds?.get("height")}"
            }
            .values
            .map { group ->
                // Just pick the first element that has any visible properties
                group.find { element ->
                    element["text"] != null || 
                    element["id"] != null || 
                    element["clickable"] != null || 
                    element["checked"] != null || 
                    element["enabled"] != null ||
                    element["className"] != null
                } ?: group.first()
            }
    }
    
    private fun createElementFromNode(node: TreeNode, bounds: ElementBounds, interactiveOnly: Boolean): JsonObject? {
        // Try multiple text sources in order of preference
        val text = listOf(
            "text", "accessibility-text", "content-desc", "label", "name", "title", "value",
            // iOS-specific attributes
            "accessibility-label", "accessibilityLabel", "accessibilityValue", "accessibilityIdentifier",
            // Android-specific attributes  
            "content-description", "hint", "placeholder"
        ).firstNotNullOfOrNull { attr ->
            node.attributes[attr]?.takeIf { it.isNotBlank() }
        }
        
        val resourceId = node.attributes["resource-id"]?.takeIf { it.isNotBlank() }
        val className = node.attributes["class"]?.takeIf { it.isNotBlank() }
        
        // Raw interactive properties - only include meaningful values
        val clickable = if (node.clickable == true) true else null
        val checked = if (node.checked == true) true else null // Only if actually checked
        val enabled = if (node.enabled == false) false else null // Only if disabled
        
        // Only filter out truly useless elements - let deduplication handle everything else
        val isObviousNoise = (
            bounds.width == 0 || bounds.height == 0 ||  // Zero-sized
            (bounds.width == 1 || bounds.height == 1) || // 1px lines
            (bounds.width < 5 && bounds.height < 5)     // Tiny spacers
        )
        
        if (isObviousNoise) return null
        
        // For interactive-only mode, still filter to meaningful content
        if (interactiveOnly) {
            val hasMeaningfulContent = text != null || resourceId != null
            val hasInteractiveProperties = clickable != null || checked != null || enabled != null
            if (!hasMeaningfulContent && !hasInteractiveProperties) return null
        }
        
        return buildJsonObject {
            putJsonObject("bounds") {
                put("x", bounds.x)
                put("y", bounds.y)
                put("width", bounds.width)
                put("height", bounds.height)
            }
            if (text != null) put("text", text)
            if (resourceId != null) put("id", resourceId)
            if (className != null) put("className", className)
            if (clickable != null) put("clickable", clickable)
            if (checked != null) put("checked", checked)
            if (enabled != null) put("enabled", enabled)
        }
    }
    
    
    private fun parseBounds(boundsStr: String?): ElementBounds? {
        if (boundsStr == null) return null
        val pattern = "\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]".toRegex()
        return pattern.find(boundsStr)?.let { match ->
            val (x1, y1, x2, y2) = match.destructured
            ElementBounds(
                x = x1.toInt(),
                y = y1.toInt(),
                width = x2.toInt() - x1.toInt(),
                height = y2.toInt() - y1.toInt()
            )
        }
    }
    
}