package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.auth.ApiKey
import maestro.cli.api.ApiClient

object DescribeCloudRunTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "describe_cloud_run",
                description = "Fetch full metadata and downloadable artifacts for a single Maestro Cloud run by its run_id. " +
                    "Artifacts may include the screen recording, simulator/xctest/emulator logs, the view hierarchy, " +
                    "a screenshots archive, and an artifacts archive — each as a short-lived signed URL. " +
                    "A run_id identifies one flow's run (not the upload_id from run_on_cloud); get a run_id from a " +
                    "dashboard run URL. Only runs created under the run-scoped storage layout expose artifacts; older " +
                    "runs return an empty artifacts list. " +
                    "Requires Maestro Cloud authentication: run `maestro login` (recommended), or set MAESTRO_CLOUD_API_KEY for non-interactive use.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        putJsonObject("run_id") {
                            put("type", "string")
                            put("description", "The run_id of the run to describe (the public run id, e.g. from a dashboard run URL).")
                        }
                    },
                    required = listOf("run_id")
                )
            )
        ) { request ->
            val originalOut = System.out
            System.setOut(System.err)
            try {
                val runId = request.arguments?.get("run_id")?.jsonPrimitive?.content
                if (runId.isNullOrBlank()) {
                    return@RegisteredTool errorResult("run_id is required")
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

                val run = try {
                    client.describeRun(apiKey, runId)
                } catch (e: ApiClient.ApiException) {
                    return@RegisteredTool if (e.statusCode == 404) {
                        errorResult("No run found with run_id=$runId for this account.")
                    } else {
                        errorResult("Failed to fetch cloud run (HTTP ${e.statusCode}) for run_id=$runId")
                    }
                }

                val result = buildJsonObject {
                    put("success", true)
                    put("run_id", run.id)
                    put("status", run.status)
                    run.failureReason?.let { put("failure_reason", it) }
                    run.resultMessage?.let { put("result_message", it) }
                    put("created_at", run.createdAt)
                    run.startedAt?.let { put("started_at", it) }
                    run.finishedAt?.let { put("finished_at", it) }
                    run.totalTimeMs?.let { put("total_time_ms", it) }
                    putJsonObject("device") {
                        put("platform", run.deviceSpec.platform)
                        put("model", run.deviceSpec.model)
                        put("os_version", run.deviceSpec.osVersion)
                    }
                    putJsonArray("artifacts") {
                        run.artifacts.forEach { artifact ->
                            addJsonObject {
                                put("type", artifact.type)
                                put("format", artifact.format)
                                put("url", artifact.url)
                                artifact.sizeBytes?.let { put("size_bytes", it) }
                            }
                        }
                    }
                }.toString()

                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to describe cloud run: ${e.message ?: e.javaClass.simpleName}")),
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
