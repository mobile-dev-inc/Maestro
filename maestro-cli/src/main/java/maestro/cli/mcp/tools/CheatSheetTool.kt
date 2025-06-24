package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.auth.ApiKey
import maestro.utils.HttpClient
import okhttp3.Request
import kotlin.time.Duration.Companion.minutes

object CheatSheetTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "cheat_sheet",
                description = "Get the Maestro cheat sheet with common commands and syntax examples. " +
                    "Returns comprehensive documentation on Maestro flow syntax, commands, and best practices.",
                inputSchema = Tool.Input(
                    properties = buildJsonObject {},
                    required = emptyList()
                )
            )
        ) { _ ->
            try {
                val apiKey = ApiKey.getToken()
                if (apiKey.isNullOrBlank()) {
                    return@RegisteredTool CallToolResult(
                        content = listOf(TextContent("MAESTRO_CLOUD_API_KEY environment variable is required")),
                        isError = true
                    )
                }
                
                val client = HttpClient.build(
                    name = "CheatSheetTool",
                    readTimeout = 2.minutes
                )
                
                // Make GET request to cheat sheet endpoint
                val httpRequest = Request.Builder()
                    .url("https://api.copilot.mobile.dev/v2/bot/maestro-cheat-sheet")
                    .header("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                
                val response = client.newCall(httpRequest).execute()
                
                response.use {
                    if (!response.isSuccessful) {
                        val errorMessage = response.body?.string().takeIf { it?.isNotEmpty() == true } ?: "Unknown error"
                        return@RegisteredTool CallToolResult(
                            content = listOf(TextContent("Failed to get cheat sheet (${response.code}): $errorMessage")),
                            isError = true
                        )
                    }
                    
                    val cheatSheetContent = response.body?.string() ?: ""
                    
                    CallToolResult(content = listOf(TextContent(cheatSheetContent)))
                }
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to get cheat sheet: ${e.message}")),
                    isError = true
                )
            }
        }
    }
}