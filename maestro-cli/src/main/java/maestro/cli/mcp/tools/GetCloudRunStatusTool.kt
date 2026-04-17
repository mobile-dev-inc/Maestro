package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.auth.ApiKey
import maestro.cli.api.ApiClient

object GetCloudRunStatusTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "get_cloud_run_status",
                description = "Fetch the current status and (optionally) per-flow results of a Maestro Cloud upload. " +
                    "Use with the upload_id and project_id returned by run_on_cloud. " +
                    "Requires Maestro Cloud authentication: run `maestro login` (recommended), or set MAESTRO_CLOUD_API_KEY for non-interactive use.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        putJsonObject("upload_id") {
                            put("type", "string")
                            put("description", "The upload_id returned by run_on_cloud.")
                        }
                        putJsonObject("project_id") {
                            put("type", "string")
                            put("description", "The project_id the upload was submitted to (also returned by run_on_cloud).")
                        }
                        putJsonObject("include_flow_results") {
                            put("type", "boolean")
                            put("description", "If true, include the per-flow breakdown once the run has completed. Default false.")
                        }
                    },
                    required = listOf("upload_id", "project_id")
                )
            )
        ) { request ->
            val originalOut = System.out
            System.setOut(System.err)
            try {
                val uploadId = request.arguments?.get("upload_id")?.jsonPrimitive?.content
                val projectId = request.arguments?.get("project_id")?.jsonPrimitive?.content
                val includeFlowResults = request.arguments?.get("include_flow_results")
                    ?.jsonPrimitive?.booleanOrNull ?: false

                if (uploadId.isNullOrBlank()) {
                    return@RegisteredTool errorResult("upload_id is required")
                }
                if (projectId.isNullOrBlank()) {
                    return@RegisteredTool errorResult("project_id is required")
                }

                val apiKey = ApiKey.getToken()
                if (apiKey.isNullOrBlank()) {
                    return@RegisteredTool errorResult(
                        "Not authenticated with Maestro Cloud. Run `maestro login` in your terminal to authenticate " +
                            "via your browser, then retry this request. For non-interactive setups, set MAESTRO_CLOUD_API_KEY."
                    )
                }

                val apiUrl = System.getenv("MAESTRO_CLOUD_API_URL")
                    ?: System.getenv("MAESTRO_API_URL")
                    ?: "https://api.copilot.mobile.dev"
                val client = ApiClient(apiUrl)

                val status = try {
                    client.uploadStatus(apiKey, uploadId, projectId)
                } catch (e: ApiClient.ApiException) {
                    return@RegisteredTool errorResult(
                        "Failed to fetch cloud run status (HTTP ${e.statusCode}) for upload_id=${uploadId}"
                    )
                }

                val result = buildJsonObject {
                    put("success", true)
                    put("upload_id", status.uploadId)
                    put("status", status.status.name)
                    put("completed", status.completed)
                    status.startTime?.let { put("started_at", it) }
                    status.totalTime?.let { put("total_time_ms", it) }
                    status.appPackageId?.let { put("app_package_id", it) }
                    put("was_app_launched", status.wasAppLaunched)
                    if (includeFlowResults) {
                        putJsonArray("flows") {
                            status.flows.forEach { flow ->
                                addJsonObject {
                                    put("name", flow.name)
                                    put("status", flow.status.name)
                                    flow.startTime?.let { put("started_at", it) }
                                    flow.totalTime?.let { put("total_time_ms", it) }
                                    if (flow.errors.isNotEmpty()) {
                                        putJsonArray("errors") {
                                            flow.errors.forEach { add(it) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }.toString()

                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to get cloud run status: ${e.message ?: e.javaClass.simpleName}")),
                    isError = true
                )
            } finally {
                System.setOut(originalOut)
            }
        }
    }

    private fun errorResult(message: String): CallToolResult {
        return CallToolResult(content = listOf(TextContent(message)), isError = true)
    }
}
