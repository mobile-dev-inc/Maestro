package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.orchestra.yaml.YamlCommandReader

object CheckFlowSyntaxTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "check_flow_syntax",
                description = "Validates the syntax of a block of Maestro code. Valid maestro code must be well-formatted YAML.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("flow_yaml") {
                            put("type", "string")
                            put("description", "YAML-formatted Maestro flow content to validate")
                        }
                    },
                    required = listOf("flow_yaml")
                )
            )
        ) { request ->
            try {
                val flowYaml = request.arguments["flow_yaml"]?.jsonPrimitive?.content
                
                if (flowYaml == null) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("flow_yaml is required")),
                        isError = true
                    )
                }
                
                val result = try {
                    YamlCommandReader.checkSyntax(flowYaml)
                    buildJsonObject {
                        put("valid", true)
                        put("message", "Flow syntax is valid")
                    }.toString()
                } catch (e: Exception) {
                    buildJsonObject {
                        put("valid", false)
                        put("error", e.message ?: "Unknown parsing error")
                        put("message", "Syntax check failed")
                    }.toString()
                }
                
                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to check flow syntax: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}