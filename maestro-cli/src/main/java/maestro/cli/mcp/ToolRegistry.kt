package maestro.cli.mcp

import kotlinx.serialization.json.JsonElement

/**
 * Registry for managing all available MCP tools
 */
class ToolRegistry {
    private val tools = mutableMapOf<String, McpTool>()
    
    fun register(tool: McpTool) {
        tools[tool.definition.name] = tool
    }
    
    fun listDefinitions(): List<ToolDefinition> {
        return tools.values.map { it.definition }
    }
    
    suspend fun execute(name: String, arguments: JsonElement): ToolCallResult {
        val tool = tools[name] 
            ?: return ToolCallResult(
                content = listOf(
                    TextContent(text = "Unknown tool: $name")
                ),
                isError = true
            )
        
        return tool.execute(arguments)
    }
    
    fun contains(toolName: String): Boolean {
        return tools.containsKey(toolName)
    }
} 