package maestro.cli.mcp.tools

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import maestro.cli.mcp.McpTool
import maestro.cli.mcp.ToolCallResult
import maestro.cli.mcp.ToolDefinition
import maestro.cli.mcp.ToolInputSchema
import maestro.cli.session.MaestroSessionManager
import maestro.cli.session.MaestroSessionManager.MaestroSession
import maestro.cli.mcp.JsonSchemaField
import maestro.Platform
import maestro.TreeNode

@Serializable
data class InspectUIParams(
    @JsonSchemaField(description = "The ID of the device to get hierarchy from.")
    val device_id: String,
    @JsonSchemaField(description = "If true, only return interactive/automatable elements. If false (default), return all visible elements that provide meaningful screen information.")
    val interactive_only: Boolean = false
)

@Serializable
data class ElementBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    override fun toString(): String = "$x,$y,${x + width},${y + height}"
}

@Serializable
data class Element(
    val bounds: ElementBounds,               // Position and size
    val text: String? = null,                // Visible text (if any)
    val id: String? = null,                  // Resource ID (if any)
    val className: String? = null,           // Raw class name (if any)
    val clickable: Boolean? = null,          // Only if true
    val checked: Boolean? = null,            // Only if has state
    val enabled: Boolean? = null             // Only if false (disabled)
)

@Serializable
data class ScreenSummary(
    val totalElements: Int,
    val interactiveElements: Int,
    val mainSections: List<String>,
    val hasScrolling: Boolean
)

@Serializable
data class InspectUIResult(
    val elements: List<Element>,             // Flat list of meaningful elements
    val summary: ScreenSummary,              // High-level screen information
    val deviceInfo: Map<String, String>      // Device metadata
)

/**
 * Tool to dump the view hierarchy of the connected device for debugging.
 * Returns a flat list of meaningful UI elements optimized for LLM consumption.
 */
class InspectUITool(private val sessionManager: MaestroSessionManager) : McpTool() {
    override val definition = generateToolDefinition<InspectUIParams>(
        "inspect_ui",
        "Dump the view hierarchy of the connected device for debugging."
    )

    override suspend fun execute(arguments: JsonElement): ToolCallResult {
        return safeExecute {
            val params = deserializeJsonParams<InspectUIParams>(arguments)

            sessionManager.newSession(
                deviceId = params.device_id,
                host = null,
                port = null,
                driverHostPort = null
            ) { session: MaestroSession ->
                val maestro = session.maestro
                
                try {
                    val viewHierarchy = maestro.viewHierarchy()
                    val deviceInfo = maestro.cachedDeviceInfo
                    
                    // Extract all meaningful elements as a flat list
                    val allElements = mutableListOf<Element>()
                    extractElements(viewHierarchy.root, allElements, params.interactive_only)
                    
                    // Deduplicate by bounds, preferring elements with meaningful content
                    val deduplicatedElements = deduplicateByBounds(allElements)
                    
                    // Generate summary information
                    val summary = generateSummary(deduplicatedElements, viewHierarchy.root)
                    
                    return@newSession successResponse(InspectUIResult(
                        elements = deduplicatedElements,
                        summary = summary,
                        deviceInfo = mapOf(
                            "platform" to deviceInfo.platform.toString(),
                            "screenSize" to "${deviceInfo.widthGrid}x${deviceInfo.heightGrid}",
                            "url" to (viewHierarchy.root.attributes["url"] ?: "")
                        ).filterValues { it.isNotEmpty() }
                    ))
                } catch (e: Exception) {
                    return@newSession errorResponse("Failed to get view hierarchy: ${e.message}")
                }
            }
        }
    }
    
    private fun extractElements(node: TreeNode, elements: MutableList<Element>, interactiveOnly: Boolean) {
        val bounds = parseBounds(node.attributes["bounds"])
        
        // Only process elements with valid bounds and meaningful content
        if (bounds != null && bounds.width > 0 && bounds.height > 0) {
            val element = createElementFromNode(node, bounds, interactiveOnly)
            if (element != null) {
                elements.add(element)
            }
        }
        
        // Process children recursively
        node.children.forEach { child ->
            extractElements(child, elements, interactiveOnly)
        }
    }
    
    private fun createElementFromNode(node: TreeNode, bounds: ElementBounds, interactiveOnly: Boolean): Element? {
        val text = node.attributes["text"]?.takeIf { it.isNotBlank() }
                ?: node.attributes["accessibility-text"]?.takeIf { it.isNotBlank() }
                ?: node.attributes["content-desc"]?.takeIf { it.isNotBlank() }
        
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
        
        return Element(
            bounds = bounds,
            text = text,
            id = resourceId,
            className = className,
            clickable = clickable,
            checked = checked,
            enabled = enabled
        )
    }
    
    private fun deduplicateByBounds(elements: List<Element>): List<Element> {
        return elements
            .groupBy { "${it.bounds.x},${it.bounds.y},${it.bounds.width},${it.bounds.height}" }
            .values
            .map { group ->
                // Prefer elements with meaningful content, then interactive properties, then just pick the first
                group.maxByOrNull { element ->
                    val hasContent = (element.text != null || element.id != null)
                    val hasInteraction = (element.clickable != null || element.checked != null || element.enabled != null) 
                    val hasClassName = element.className != null
                    
                    when {
                        hasContent -> 3  // Highest priority: has text or ID
                        hasInteraction -> 2  // Medium priority: has interactive properties
                        hasClassName -> 1  // Low priority: has className
                        else -> 0  // Lowest priority: empty element
                    }
                } ?: group.first()
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
    
    private fun generateSummary(elements: List<Element>, root: TreeNode): ScreenSummary {
        val interactiveElements = elements.count { it.clickable == true || it.checked != null }
        
        // Try to identify main sections based on text content
        val mainSections = elements
            .mapNotNull { it.text }
            .filter { it.length > 3 && it.length < 50 }
            .take(5) // Limit to top 5 sections
        
        // Check for scrolling capability
        val hasScrolling = root.attributes.containsKey("scrollable") || elements.size > 10
        
        return ScreenSummary(
            totalElements = elements.size,
            interactiveElements = interactiveElements,
            mainSections = mainSections,
            hasScrolling = hasScrolling
        )
    }
} 