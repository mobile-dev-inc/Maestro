package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import maestro.auth.ApiKey
import maestro.cli.mcp.MaestroTool
import maestro.cli.mcp.schema.McpToolInput
import maestro.utils.HttpClient
import okhttp3.Request
import kotlin.time.Duration.Companion.minutes

// Schema definitions for this tool
@Serializable
data class CheatSheetInput(
    // No fields needed for empty input
    val dummy: String? = null
) : McpToolInput

@Serializable
data class CheatSheetOutput(
    val success: Boolean,
    val content: String,
    val message: String
)

object CheatSheetTool {
    fun create(): RegisteredTool {
        return MaestroTool.create<CheatSheetInput, CheatSheetOutput>(
            name = "cheat_sheet",
            description = "Get the Maestro cheat sheet with common commands and syntax examples. " +
                "Returns comprehensive documentation on Maestro flow syntax, commands, and best practices."
        ) { input ->
            val apiKey = ApiKey.getToken()
            if (apiKey.isNullOrBlank()) {
                throw Exception("MAESTRO_CLOUD_API_KEY environment variable is required")
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
                    throw Exception("Failed to get cheat sheet (${response.code}): $errorMessage")
                }
                
                val cheatSheetContent = response.body?.string() ?: ""
                
                CheatSheetOutput(
                    success = true,
                    content = cheatSheetContent,
                    message = "Cheat sheet retrieved successfully"
                )
            }
        }
    }
}