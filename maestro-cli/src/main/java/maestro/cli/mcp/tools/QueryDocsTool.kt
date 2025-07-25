package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import maestro.auth.ApiKey
import maestro.cli.mcp.MaestroTool
import maestro.cli.mcp.schema.McpToolInput
import maestro.utils.HttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.time.Duration.Companion.minutes

// Schema definitions for this tool
@Serializable
data class QueryDocsInput(
    val question: String
) : McpToolInput

@Serializable
data class QueryDocsOutput(
    val answer: String,
    val success: Boolean = true
)

object QueryDocsTool {
    fun create(): RegisteredTool {
        return MaestroTool.create<QueryDocsInput, QueryDocsOutput>(
            name = "query_docs",
            description = "Query the Maestro documentation for specific information. " +
                "Ask questions about Maestro features, commands, best practices, and troubleshooting. " +
                "Returns relevant documentation content and examples."
        ) { input ->
            val apiKey = ApiKey.getToken()
            if (apiKey.isNullOrBlank()) {
                throw Exception("MAESTRO_CLOUD_API_KEY environment variable is required")
            }
            
            val client = HttpClient.build(
                name = "QueryDocsTool",
                readTimeout = 2.minutes
            )
            
            // Create JSON request body
            val requestBody = buildJsonObject {
                put("question", input.question)
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
                    throw Exception("Failed to query docs (${response.code}): $errorMessage")
                }
                
                val responseBody = response.body?.string() ?: ""
                
                try {
                    val jsonResponse = Json.parseToJsonElement(responseBody).jsonObject
                    val answer = jsonResponse["answer"]?.jsonPrimitive?.content ?: responseBody
                    QueryDocsOutput(answer = answer)
                } catch (e: Exception) {
                    // If JSON parsing fails, return the raw response
                    QueryDocsOutput(answer = responseBody)
                }
            }
        }
    }
}