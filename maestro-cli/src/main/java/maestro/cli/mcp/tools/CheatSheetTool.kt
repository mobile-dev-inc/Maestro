package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
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
                inputSchema = ToolSchema(
                    properties = buildJsonObject {},
                    required = emptyList()
                )
            )
        ) { _ ->
            try {
                val client = HttpClient.build(
                    name = "CheatSheetTool",
                    readTimeout = 2.minutes
                )

                val httpRequest = Request.Builder()
                    .url("https://api.copilot.mobile.dev/v2/bot/maestro-cheat-sheet")
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