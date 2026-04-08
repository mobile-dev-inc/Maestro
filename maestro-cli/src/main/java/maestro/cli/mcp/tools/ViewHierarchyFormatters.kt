package maestro.cli.mcp.tools

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import maestro.TreeNode

/**
 * Various formatters for view hierarchy output.
 * Preserves different approaches for potential future use.
 */
object ViewHierarchyFormatters {
    
    /**
     * Original CSV format - matches PrintHierarchyCommand.kt exactly
     */
    fun extractCsvOutput(node: TreeNode?): String {
        if (node == null) return "element_num,depth,bounds,attributes,parent_num\n"
        
        val csv = StringBuilder()
        csv.appendLine("element_num,depth,bounds,attributes,parent_num")
        
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
        node: TreeNode?, 
        depth: Int, 
        parentId: Int?, 
        nodeToId: Map<TreeNode, Int>,
        csv: StringBuilder
    ) {
        if (node == null) return
        
        val nodeId = nodeToId[node] ?: return
        
        // Extract bounds as separate column
        val bounds = node.attributes["bounds"] ?: ""
        val quotedBounds = if (bounds.isNotEmpty()) "\"$bounds\"" else ""
        
        // Build attributes string (exclude bounds since it's now a separate column)
        val attributesList = mutableListOf<String>()
        
        // Add normal attributes (skip boolean properties and bounds that are handled separately)
        val excludedProperties = setOf("clickable", "enabled", "focused", "checked", "selected", "bounds")
        node.attributes.forEach { (key, value) ->
            if (value.isNotEmpty() && value != "false" && !excludedProperties.contains(key)) {
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
        csv.append("$nodeId,$depth,$quotedBounds,\"$escapedAttributes\",${parentId ?: ""}\n")
        
        // Process children
        node.children.forEach { child ->
            processTreeToCSV(child, depth + 1, nodeId, nodeToId, csv)
        }
    }
    
    /**
     * Compact CSV format with filtering and abbreviated columns
     * 
     * Example output:
     * id,depth,bounds,text,resource_id,accessibility,hint,class,value,scrollable,clickable,enabled,focused,selected,checked,parent_id
     * 0,0,"[9,22][402,874]",,,"Demo App",,,,,1,,,,,
     * 1,1,"[63,93][347,128]",,,"Flutter Demo Home Page",,,,,1,,,,,0
     * 2,1,"[131,139][279,187]",,,"Defects Test",,,,,1,,,,,0
     * 3,1,"[330,768][386,824]",,"fabAddIcon","Increment",,,,,1,,,,,0
     */
    fun extractCompactCsvOutput(node: TreeNode, platform: String): String {
        val csv = StringBuilder()
        csv.appendLine("id,depth,bounds,text,resource_id,accessibility,hint,class,value,scrollable,clickable,enabled,focused,selected,checked,parent_id")
        
        val compactElements = compactTreeData(node, platform)
        val flatElements = mutableListOf<Pair<Map<String, Any?>, Int>>() // element, depth
        
        // Flatten the nested structure while preserving depth
        fun flattenElements(elements: List<Map<String, Any?>>, depth: Int) {
            elements.forEach { element ->
                flatElements.add(element to depth)
                @Suppress("UNCHECKED_CAST")
                val children = element["c"] as? List<Map<String, Any?>>
                if (children != null) {
                    flattenElements(children, depth + 1)
                }
            }
        }
        
        flattenElements(compactElements, 0)
        
        // Build CSV rows
        flatElements.forEachIndexed { index, (element, depth) ->
            val bounds = element["b"] as? String ?: ""
            val text = element["txt"] as? String ?: ""
            val resourceId = element["rid"] as? String ?: ""
            val accessibility = element["a11y"] as? String ?: ""
            val hint = element["hint"] as? String ?: ""
            val className = element["cls"] as? String ?: ""
            val value = element["val"] as? String ?: ""
            val scrollable = if (element["scroll"] == true) "1" else ""
            val clickable = if (element["clickable"] == true) "1" else ""
            val enabled = if (element["enabled"] == false) "0" else "1"
            val focused = if (element["focused"] == true) "1" else ""
            val selected = if (element["selected"] == true) "1" else ""
            val checked = if (element["checked"] == true) "1" else ""
            
            // Find parent ID (previous element with lower depth)
            var parentId = ""
            for (i in index - 1 downTo 0) {
                if (flatElements[i].second < depth) {
                    parentId = i.toString()
                    break
                }
            }
            
            // Quote strings only if they have content
            val quotedBounds = quoteIfNotEmpty(bounds)
            val quotedText = quoteIfNotEmpty(text)
            val quotedA11y = quoteIfNotEmpty(accessibility)
            val quotedHint = quoteIfNotEmpty(hint)
            val quotedClass = quoteIfNotEmpty(className)
            val quotedValue = quoteIfNotEmpty(value)
            val quotedRid = quoteIfNotEmpty(resourceId)
            
            csv.append("$index,$depth,$quotedBounds,$quotedText,$quotedRid,$quotedA11y,$quotedHint,$quotedClass,$quotedValue,$scrollable,$clickable,$enabled,$focused,$selected,$checked,$parentId\n")
        }
        
        return csv.toString()
    }
    
    private fun quoteIfNotEmpty(value: String): String {
        return if (value.isNotEmpty()) {
            "\"${value.replace("\"", "\"\"")}\""
        } else {
            ""
        }
    }
    
    /**
     * Compact JSON format with filtering and abbreviated keys
     */
    fun extractCompactJsonOutput(node: TreeNode, platform: String): String {
        val compactData = createCompactWithSchema(node, platform)
        return jacksonObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .writeValueAsString(compactData)
    }
    
    /**
     * Compact YAML format with filtering and abbreviated keys
     */
    fun extractCompactYamlOutput(node: TreeNode, platform: String): String {
        val yamlMapper = YAMLMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL)
        val result = StringBuilder()
        
        // Get the full data structure
        val compactData = createCompactWithSchema(node, platform)
        
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
    
    private fun createCompactWithSchema(node: TreeNode, platform: String): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        
        // Add platform-specific schema
        result["ui_schema"] = createPlatformSchema(platform)
        
        // Convert the tree to compact structure using existing logic
        result["elements"] = compactTreeData(node, platform)
        
        return result
    }
    
    private fun createPlatformSchema(platform: String): Map<String, Any?> {
        return when (platform) {
            "android" -> createAndroidSchema()
            "ios" -> createIOSSchema()
            else -> createIOSSchema() // Default to iOS if unknown
        }
    }
    
    private fun createAndroidSchema(): Map<String, Any?> {
        return mapOf(
            "platform" to "android",
            "abbreviations" to mapOf(
                "b" to "bounds",
                "txt" to "text",
                "rid" to "resource-id",
                "a11y" to "content-desc",
                "hint" to "hintText",
                "cls" to "class",
                "scroll" to "scrollable",
                "c" to "children"
            ),
            "defaults" to mapOf(
                "enabled" to true,
                "clickable" to false,
                "focused" to false,
                "selected" to false,
                "checked" to false,
                "scrollable" to false,
                "txt" to "",
                "hint" to "",
                "rid" to "",
                "a11y" to "",
                "cls" to ""
            )
        )
    }
    
    private fun createIOSSchema(): Map<String, Any?> {
        return mapOf(
            "platform" to "ios",
            "abbreviations" to mapOf(
                "b" to "bounds",
                "txt" to "text",
                "rid" to "resource-id",
                "a11y" to "accessibilityText",
                "hint" to "hintText",
                "val" to "value",
                "c" to "children"
            ),
            "defaults" to mapOf(
                "enabled" to true,
                "focused" to false,
                "selected" to false,
                "checked" to false,
                "txt" to "",
                "hint" to "",
                "rid" to "",
                "val" to "",
                "a11y" to ""
            )
        )
    }
    
    /**
     * Recursively processes the UI tree to create a compact representation by:
     * 1. Filtering out meaningless nodes (zero-size, empty containers)
     * 2. Converting remaining nodes to abbreviated attribute maps
     * 3. Flattening the hierarchy by removing wrapper containers
     */
    private fun compactTreeData(node: TreeNode, platform: String): List<Map<String, Any?>> {
        // Skip zero-size elements (invisible/collapsed elements serve no automation purpose)
        if (hasZeroSize(node)) {
            return node.children.flatMap { compactTreeData(it, platform) }
        }
        
        // Skip nodes with no meaningful content (empty containers that only provide structure)
        if (!hasNonDefaultValues(node, platform)) {
            return node.children.flatMap { compactTreeData(it, platform) }
        }
        
        // Process this node - convert to compact representation with abbreviated attributes
        val element = convertToCompactNode(node).toMutableMap()
        
        // Recursively process children with same filtering rules
        val children = node.children.flatMap { compactTreeData(it, platform) }
        
        // Add children array only if there are meaningful child elements
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
    
    private fun hasNonDefaultValues(node: TreeNode, platform: String): Boolean {
        // Check for non-default boolean states (TreeNode properties)
        if (node.clickable == true) return true
        if (node.checked == true) return true
        if (node.enabled == false) return true  // False is non-default
        if (node.focused == true) return true
        if (node.selected == true) return true
        
        // Get platform-specific defaults from schema
        val schema = createPlatformSchema(platform)
        @Suppress("UNCHECKED_CAST")
        val defaults = schema["defaults"] as Map<String, Any?>
        
        // Check all attributes against their defaults
        for ((attr, value) in node.attributes) {
            if (value.isNullOrBlank()) continue
            
            val defaultValue = defaults[attr]
            val isNonDefault = when (defaultValue) {
                is String -> value != defaultValue
                is Boolean -> value.toBooleanStrictOrNull() != defaultValue
                is Int -> value.toIntOrNull() != defaultValue
                else -> !value.isNullOrBlank() // For attributes not in defaults, non-blank means meaningful
            }
            if (isNonDefault) return true
        }
        
        return false
    }
    
    /**
     * Converts a TreeNode to a compact map representation by:
     * 1. Inlining attributes directly into the output (no nested "attributes" object)
     * 2. Using abbreviated keys (e.g., "b" for "bounds", "txt" for "text") 
     * 3. Including only non-default values to minimize output size
     * 4. Flattening the structure for easier LLM consumption
     */
    private fun convertToCompactNode(node: TreeNode): Map<String, Any?> {
        val result = mutableMapOf<String, Any?>()
        
        // Inline attributes with abbreviated keys (only if non-empty)
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
        
        // For Android, also check content-desc (maps to a11y in schema)
        val contentDesc = node.attributes["content-desc"]
        if (!contentDesc.isNullOrBlank()) result["a11y"] = contentDesc
        
        val hintText = node.attributes["hintText"]
        if (!hintText.isNullOrBlank()) result["hint"] = hintText
        
        val scrollable = node.attributes["scrollable"]
        if (scrollable == "true") result["scroll"] = true
        
        // Inline TreeNode boolean properties (only if non-default)
        if (node.clickable == true) result["clickable"] = true
        if (node.checked == true) result["checked"] = true
        if (node.enabled == false) result["enabled"] = false  // false is non-default
        if (node.focused == true) result["focused"] = true
        if (node.selected == true) result["selected"] = true
        
        return result
    }
}