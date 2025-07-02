package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.cli.session.MaestroSessionManager
import maestro.TreeNode
import kotlinx.coroutines.runBlocking
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory

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
                        putJsonObject("format") {
                            put("type", "string")
                            put("description", "Output format: yaml, json, or csv (default: yaml)")
                            put("enum", buildJsonArray {
                                add("yaml")
                                add("json") 
                                add("csv")
                            })
                        }
                    },
                    required = listOf("device_id")
                )
            )
        ) { request ->
            try {
                val deviceId = request.arguments["device_id"]?.jsonPrimitive?.content
                val format = request.arguments["format"]?.jsonPrimitive?.content ?: "yaml"
                
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
                    
                    // Return format based on parameters
                    when (format.lowercase()) {
                        "yaml" -> {
                            buildYamlOutput(tree)
                        }
                        "json" -> {
                            val compactData = createCompactWithSchema(tree)
                            jacksonObjectMapper()
                                .setSerializationInclusion(JsonInclude.Include.NON_NULL)
                                .writerWithDefaultPrettyPrinter()
                                .writeValueAsString(compactData)
                        }
                        "csv" -> extractCsvOutput(tree)
                        else -> {
                            buildYamlOutput(tree)
                        } // default fallback
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
    
    private fun createCompactWithSchema(node: TreeNode): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        
        // Add schema section (same for both JSON and YAML)
        result["ui_schema"] = mapOf(
            "abbreviations" to mapOf(
                "b" to "bounds",
                "a11y" to "accessibilityText",
                "val" to "value", 
                "txt" to "text",
                "rid" to "resource-id",
                "cls" to "class",
                "hint" to "hintText",
                "title" to "title",
                "pkg" to "package",
                "scroll" to "scrollable",
                "longClick" to "long-clickable",
                "pwd" to "password",
                "url" to "url",
                "synth" to "synthetic",
                "c" to "children"
            ),
            "defaults" to mapOf(
                "enabled" to true,
                "focused" to false,
                "selected" to false,
                "checked" to false,
                "clickable" to false,
                "scrollable" to false,
                "long-clickable" to false,
                "password" to false,
                "synthetic" to false,
                "txt" to "",
                "hint" to "",
                "rid" to "",
                "title" to "",
                "package" to "",
                "url" to ""
            )
        )
        
        // Convert the tree to compact structure using existing logic
        result["elements"] = compactTreeData(node)
        
        return result
    }
    
    private fun compactTreeData(node: TreeNode): List<Map<String, Any?>> {
        // Skip zero-size elements
        if (hasZeroSize(node)) {
            return node.children.flatMap { compactTreeData(it) }
        }
        
        // Skip nodes with no meaningful content
        if (!hasNonDefaultValues(node)) {
            return node.children.flatMap { compactTreeData(it) }
        }
        
        // Process this node normally
        val element = convertToCompactNode(node).toMutableMap()
        val children = node.children.flatMap { compactTreeData(it) }
        
        if (children.isNotEmpty()) {
            element["c"] = children
        }
        
        return listOf(element)
    }
    
    private fun hasZeroSize(node: TreeNode): Boolean {
        val bounds = node.attributes["bounds"] ?: return false
        val boundsPattern = Regex("\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]")
        val matchResult = boundsPattern.find(bounds) ?: return false
        val (x1, y1, x2, y2) = matchResult.destructured
        val width = x2.toInt() - x1.toInt()
        val height = y2.toInt() - y1.toInt()
        return width == 0 || height == 0
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
    
    private fun buildYamlOutput(tree: TreeNode): String {
        val yamlMapper = YAMLMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
        val result = StringBuilder()
        
        // Get the full data structure
        val compactData = createCompactWithSchema(tree)
        
        // Add document separator
        result.appendLine("---")
        
        // Serialize and add ui_schema section with comment
        result.appendLine("# Schema definitions - explains abbreviations and default values used in elements")
        val schemaYaml = yamlMapper.writeValueAsString(mapOf("ui_schema" to compactData["ui_schema"]))
            .removePrefix("---\n") // Remove extra document separator
        result.append(schemaYaml)
        
        // Serialize and add elements section with comment  
        result.appendLine("# UI Elements - the actual view hierarchy with abbreviated attribute names")
        val elementsYaml = yamlMapper.writeValueAsString(mapOf("elements" to compactData["elements"]))
            .removePrefix("---\n") // Remove extra document separator
        result.append(elementsYaml)
        
        return result.toString()
    }
    
    private fun convertToCompactNode(node: TreeNode): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        
        // Add abbreviated attributes only if non-default
        val bounds = node.attributes["bounds"]
        if (!bounds.isNullOrBlank()) result["b"] = bounds
        
        val accessibilityText = node.attributes["accessibilityText"]
        if (!accessibilityText.isNullOrBlank()) result["a11y"] = accessibilityText
        
        val text = node.attributes["text"]
        if (!text.isNullOrBlank()) result["txt"] = text
        
        val value = node.attributes["value"]
        if (!value.isNullOrBlank()) result["val"] = value
        
        val resourceId = node.attributes["resource-id"]
        if (!resourceId.isNullOrBlank()) result["rid"] = resourceId
        
        val className = node.attributes["class"]
        if (!className.isNullOrBlank()) result["cls"] = className
        
        // Add non-default boolean states
        if (node.clickable == true) result["clickable"] = true
        if (node.checked == true) result["checked"] = true
        if (node.enabled == false) result["enabled"] = false
        if (node.focused == true) result["focused"] = true
        if (node.selected == true) result["selected"] = true
        
        // Children are handled in compactTreeData() to respect filtering
        
        return result
    }
    
    private fun extractCsvOutput(node: TreeNode?): String {
        if (node == null) return "element_num,depth,attributes,parent_num\n"
        
        val csv = StringBuilder()
        csv.appendLine("element_num,depth,attributes,parent_num")
        
        val nodeToId = mutableMapOf<TreeNode, Int>()
        
        // Assign IDs to each node
        var counter = 0
        node.aggregate().forEach { treeNode ->
            nodeToId[treeNode] = counter++
        }
        
        // Process tree recursively to generate CSV
        processTreeToCSV(node, 0, null, nodeToId, csv)
        
        return csv.toString()
    }
    
    private fun processTreeToCSV(
        node: TreeNode, 
        depth: Int, 
        parentId: Int?, 
        nodeToId: Map<TreeNode, Int>,
        csv: StringBuilder
    ) {
        val nodeId = nodeToId[node] ?: return
        
        // Build attributes string
        val attributesList = mutableListOf<String>()
        
        // Add normal attributes
        node.attributes.forEach { (key, value) ->
            if (value.isNotEmpty() && value != "false") {
                attributesList.add("$key=$value")
            }
        }
        
        // Add boolean properties if true
        if (node.clickable == true) attributesList.add("clickable=true")
        if (node.enabled == true) attributesList.add("enabled=true")
        if (node.focused == true) attributesList.add("focused=true")
        if (node.checked == true) attributesList.add("checked=true")
        if (node.selected == true) attributesList.add("selected=true")
        
        // Join all attributes with "; "
        val attributesString = attributesList.joinToString("; ")
        
        // Escape quotes in the attributes string if needed
        val escapedAttributes = attributesString.replace("\"", "\"\"")
        
        // Add this node to CSV
        csv.append("$nodeId,$depth,\"$escapedAttributes\",${parentId ?: ""}\n")
        
        // Process children
        node.children.forEach { child ->
            processTreeToCSV(child, depth + 1, nodeId, nodeToId, csv)
        }
    }
    
}