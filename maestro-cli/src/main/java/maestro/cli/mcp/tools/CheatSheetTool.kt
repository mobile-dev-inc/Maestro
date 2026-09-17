package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.utils.HttpClient
import okhttp3.Request
import kotlin.time.Duration.Companion.minutes

object CheatSheetTool {

    // Resolved the same way every other cloud-facing MCP tool resolves it (run_on_cloud,
    // list_cloud_devices, get_cloud_run_status). This tool used to hardcode the prod URL, which
    // made it the only one that could not be pointed at a local backend -- and the only one with
    // no test, because there was no way to stand a server in front of it.
    private fun cheatSheetUrl(): String {
        val base = System.getenv("MAESTRO_CLOUD_API_URL")
            ?: System.getenv("MAESTRO_API_URL")
            ?: "https://api.copilot.mobile.dev"
        return "${base.trimEnd('/')}/v2/bot/maestro-cheat-sheet"
    }

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
                    .url(cheatSheetUrl())
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