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
                    },
                    required = listOf("device_id")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                
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
                    val tree = viewHierarchy.root
                    
                    // Always return YAML format
                    extractYamlOutput(tree)
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
    
    private fun extractYamlOutput(node: TreeNode?): String {
        if (node == null) return ""
        
        val yaml = StringBuilder()
        
        // Add schema header
        yaml.appendLine("# UI Schema - provides context for abbreviations and defaults")
        yaml.appendLine("ui_schema:")
        yaml.appendLine(" abbreviations:")
        yaml.appendLine("  b: bounds")
        yaml.appendLine("  a11y: accessibilityText")
        yaml.appendLine("  val: value")
        yaml.appendLine("  txt: text")
        yaml.appendLine("  rid: resource-id")
        yaml.appendLine("  cls: class                    # Android/Web")
        yaml.appendLine("  hint: hintText")
        yaml.appendLine("  title: title                 # iOS")
        yaml.appendLine("  pkg: package                 # Android")
        yaml.appendLine("  scroll: scrollable           # Android")
        yaml.appendLine("  longClick: long-clickable    # Android")
        yaml.appendLine("  pwd: password                # Android")
        yaml.appendLine("  url: url                     # Web")
        yaml.appendLine("  synth: synthetic             # Web")
        yaml.appendLine("  c: children")
        yaml.appendLine(" defaults:")
        yaml.appendLine("  enabled: true")
        yaml.appendLine("  focused: false")
        yaml.appendLine("  selected: false")
        yaml.appendLine("  checked: false")
        yaml.appendLine("  clickable: false")
        yaml.appendLine("  scrollable: false            # Android")
        yaml.appendLine("  long-clickable: false        # Android")
        yaml.appendLine("  password: false              # Android")
        yaml.appendLine("  synthetic: false             # Web")
        yaml.appendLine("  txt: \"\"")
        yaml.appendLine("  hint: \"\"")
        yaml.appendLine("  rid: \"\"")
        yaml.appendLine("  title: \"\"                    # iOS")
        yaml.appendLine("  package: \"\"                  # Android")
        yaml.appendLine("  url: \"\"                      # Web")
        yaml.appendLine("")
        yaml.appendLine("# UI Elements")
        yaml.appendLine("elements:")
        
        // Process the tree
        val processedElements = processYamlNode(node)
        if (processedElements.isNotEmpty()) {
            processedElements.forEach { element ->
                yaml.appendLine(element)
            }
        }
        
        return yaml.toString()
    }
    
    private fun processYamlNode(node: TreeNode, depth: Int = 0): List<String> {
        val elements = mutableListOf<String>()
        val indent = " ".repeat(depth + 1)
        
        // Filter out zero width/height elements
        val bounds = node.attributes["bounds"]
        val hasZeroSize = bounds?.let {
            val boundsPattern = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
            val matchResult = boundsPattern.find(it)
            if (matchResult != null) {
                val (x1, y1, x2, y2) = matchResult.destructured
                val width = x2.toInt() - x1.toInt()
                val height = y2.toInt() - y1.toInt()
                width == 0 || height == 0
            } else false
        } ?: false
        
        if (hasZeroSize) {
            // Process children but skip this node
            node.children.forEach { child ->
                elements.addAll(processYamlNode(child, depth))
            }
            return elements
        }
        
        // Process children first to check for container deduplication
        val childElements = mutableListOf<String>()
        node.children.forEach { child ->
            childElements.addAll(processYamlNode(child, depth + 1))
        }
        
        // Check if this is a container with exact same size as single child and no meaningful content
        val shouldSkipContainer = shouldSkipAsContainer(node, childElements)
        
        if (shouldSkipContainer) {
            // Skip this container and return children with reduced depth
            node.children.forEach { child ->
                elements.addAll(processYamlNode(child, depth))
            }
        } else {
            // Include this element
            val elementYaml = buildElementYaml(node, indent)
            if (elementYaml.isNotEmpty()) {
                elements.add(elementYaml)
                // Add children with proper indentation
                if (childElements.isNotEmpty()) {
                    val childIndent = " ".repeat(depth + 2)
                    elements.add("${indent}c:")
                    childElements.forEach { child ->
                        elements.add(child)
                    }
                }
            } else if (childElements.isNotEmpty()) {
                // Element has no content but has children - add children at current level
                elements.addAll(childElements)
            }
        }
        
        return elements
    }
    
    private fun shouldSkipAsContainer(node: TreeNode, childElements: List<String>): Boolean {
        // Skip if this node has exactly same bounds as a single child and no meaningful attributes
        if (node.children.size != 1) return false
        
        val child = node.children.first()
        val nodeBounds = node.attributes["bounds"]
        val childBounds = child.attributes["bounds"]
        
        if (nodeBounds != childBounds) return false
        
        // Check if node has any meaningful attributes beyond defaults
        val hasMeaningfulContent = hasNonDefaultValues(node)
        
        return !hasMeaningfulContent
    }
    
    private fun hasNonDefaultValues(node: TreeNode): Boolean {
        // Check for non-default text attributes
        val textAttrs = listOf("accessibilityText", "text", "hintText", "resource-id", "title", "package", "url")
        for (attr in textAttrs) {
            val value = node.attributes[attr]
            if (!value.isNullOrBlank()) return true
        }
        
        // Check for non-default boolean states
        if (node.clickable == true) return true
        if (node.checked == true) return true
        if (node.enabled == false) return true  // False is non-default
        if (node.focused == true) return true
        if (node.selected == true) return true
        
        // Check for non-default boolean attributes (stored as strings)
        val booleanAttrs = mapOf(
            "scrollable" to "false",
            "long-clickable" to "false", 
            "password" to "false",
            "synthetic" to "false"
        )
        for ((attr, defaultValue) in booleanAttrs) {
            val value = node.attributes[attr]
            if (value != null && value != defaultValue) return true
        }
        
        // Check for other meaningful attributes
        val value = node.attributes["value"]
        if (!value.isNullOrBlank()) return true
        
        val className = node.attributes["class"]
        if (!className.isNullOrBlank()) return true
        
        return false
    }
    
    private fun buildElementYaml(node: TreeNode, indent: String): String {
        val parts = mutableListOf<String>()
        
        // Add accessibility text (most important)
        val a11y = node.attributes["accessibilityText"]
        if (!a11y.isNullOrBlank()) {
            parts.add("a11y: \"$a11y\"")
        }
        
        // Add regular text if no accessibility text
        if (a11y.isNullOrBlank()) {
            val txt = node.attributes["text"]
            if (!txt.isNullOrBlank()) {
                parts.add("txt: \"$txt\"")
            }
        }
        
        // Add value
        val valueAttr = node.attributes["value"]
        if (!valueAttr.isNullOrBlank()) {
            parts.add("val: \"$valueAttr\"")
        }
        
        // Add resource ID
        val resourceId = node.attributes["resource-id"]
        if (!resourceId.isNullOrBlank()) {
            parts.add("rid: \"$resourceId\"")
        }
        
        // Add class
        val className = node.attributes["class"]
        if (!className.isNullOrBlank()) {
            parts.add("cls: \"$className\"")
        }
        
        // Add hint text
        val hint = node.attributes["hintText"]
        if (!hint.isNullOrBlank()) {
            parts.add("hint: \"$hint\"")
        }
        
        // Add title (iOS)
        val title = node.attributes["title"]
        if (!title.isNullOrBlank()) {
            parts.add("title: \"$title\"")
        }
        
        // Add package (Android)
        val pkg = node.attributes["package"]
        if (!pkg.isNullOrBlank()) {
            parts.add("pkg: \"$pkg\"")
        }
        
        // Add URL (Web)
        val url = node.attributes["url"]
        if (!url.isNullOrBlank()) {
            parts.add("url: \"$url\"")
        }
        
        // Add Android-specific boolean attributes (only if non-default)
        val scrollable = node.attributes["scrollable"]
        if (scrollable == "true") {
            parts.add("scroll: true")
        }
        
        val longClickable = node.attributes["long-clickable"]
        if (longClickable == "true") {
            parts.add("longClick: true")
        }
        
        val password = node.attributes["password"]
        if (password == "true") {
            parts.add("pwd: true")
        }
        
        // Add Web-specific boolean attributes (only if non-default)
        val synthetic = node.attributes["synthetic"]
        if (synthetic == "true") {
            parts.add("synth: true")
        }
        
        // Add any other unknown attributes with full names (to ensure we don't lose data)
        val knownAttributes = setOf(
            "accessibilityText", "text", "value", "resource-id", "class", "hintText", 
            "title", "package", "url", "scrollable", "long-clickable", "password", 
            "synthetic", "bounds", "enabled", "focused", "selected", "checked"
        )
        
        for ((key, value) in node.attributes) {
            if (!knownAttributes.contains(key) && !value.isNullOrBlank()) {
                parts.add("$key: \"$value\"")
            }
        }
        
        // Add bounds
        val bounds = node.attributes["bounds"]
        if (!bounds.isNullOrBlank()) {
            parts.add("b: \"$bounds\"")
        }
        
        // Add non-default boolean states (from TreeNode properties, not attributes)
        if (node.clickable == true) parts.add("clickable: true")
        if (node.checked == true) parts.add("checked: true")
        if (node.enabled == false) parts.add("enabled: false")
        if (node.focused == true) parts.add("focused: true")
        if (node.selected == true) parts.add("selected: true")
        
        return if (parts.isNotEmpty()) {
            "${indent}- ${parts.joinToString(", ")}"
        } else {
            ""
        }
    }
    
}