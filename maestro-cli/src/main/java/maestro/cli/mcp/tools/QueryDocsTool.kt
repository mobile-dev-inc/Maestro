package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.auth.ApiKey
import maestro.utils.HttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.time.Duration.Companion.minutes

object QueryDocsTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "query_docs",
                description = "Query the Maestro documentation for specific information. " +
                    "Ask questions about Maestro features, commands, best practices, and troubleshooting. " +
                    "Returns relevant documentation content and examples.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {
                        putJsonObject("question") {
                            put("type", "string")
                            put("description", "The question to ask about Maestro documentation")
                        }
                    },
                    required = listOf("question")
                )
            )
        ) { request ->
            try {
                val question = request.arguments["question"]?.jsonPrimitive?.content
                if (question.isNullOrBlank()) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("question parameter is required")),
                        isError = true
                    )
                }
                
                val apiKey = ApiKey.getToken()
                if (apiKey.isNullOrBlank()) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("MAESTRO_CLOUD_API_KEY environment variable is required")),
                        isError = true
                    )
                }
                
                val client = HttpClient.build(
                    name = "QueryDocsTool",
                    readTimeout = 2.minutes
                )
                
                // Create JSON request body
                val requestBody = buildJsonObject {
                    put("question", question)
                }.toString()
                
                // Make POST request to query docs endpoint
                val httpRequest = Request.Builder()
                    .url("https://api.copilot.mobile.dev/v2/bot/query-docs")
                    .header("Authorization", "Bearer $apiKey")
                    .header("Content-Type", "application/json")
                    .post(requestBody.toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = client.newCall(httpRequest).execute()
                
                response.use {
                    if (!response.isSuccessful) {
                        val errorMessage = response.body?.string().takeIf { it?.isNotEmpty() == true } ?: "Unknown error"
                        return@RegisteredTool CallToolResult(
                            content = listOf(TextContent("Failed to query docs (${response.code}): $errorMessage")),
                            isError = true
                        )
                    }
                    
                    val responseBody = response.body?.string() ?: ""
                    
                    try {
                        val jsonResponse = Json.parseToJsonElement(responseBody).jsonObject
                        val answer = jsonResponse["answer"]?.jsonPrimitive?.content ?: responseBody
                        CallToolResult(content = listOf(TextContent(answer)))
                    } catch (e: Exception) {
                        // If JSON parsing fails, return the raw response
                        CallToolResult(content = listOf(TextContent(responseBody)))
                    }
                }
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to query docs: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}