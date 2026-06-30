package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.auth.ApiKey
import maestro.cli.api.ApiClient
import maestro.cli.api.RunArtifact

object DescribeCloudRunTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "describe_cloud_run",
                description = "Fetch metadata and downloadable artifacts for a single Maestro Cloud run by its run_id. " +
                    "Returns run status, failure reason, device spec, timing, and a list of artifacts — each a short-lived " +
                    "signed URL (screen recording, simulator/xctest/emulator logs, and the view hierarchy). " +
                    "IMPORTANT: run_id is the per-flow run id from a dashboard run URL, NOT the upload_id returned by " +
                    "run_on_cloud. Older runs created before run-scoped artifact storage return an empty artifacts list. " +
                    "Requires Maestro Cloud authentication: run `maestro login` (recommended), or set MAESTRO_CLOUD_API_KEY for non-interactive use.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        putJsonObject("run_id") {
                            put("type", "string")
                            put("description", "The per-flow run id from a dashboard run URL (NOT the upload_id from run_on_cloud).")
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
                    return@RegisteredTool when (e.statusCode) {
                        null -> errorResult(
                            "Could not reach Maestro Cloud to describe run_id=$runId. " +
                                "Check your network connection and retry."
                        )
                        401 -> errorResult(
                            "Not authenticated with Maestro Cloud. Run `maestro login` in your terminal to authenticate " +
                                "via your browser, then retry this request. For non-interactive setups, set MAESTRO_CLOUD_API_KEY."
                        )
                        404 -> errorResult(
                            "No run found with run_id=$runId. Check the run_id (it is the per-flow run id from a dashboard " +
                                "run URL, not the upload_id from run_on_cloud) and that it belongs to your organization."
                        )
                        409 -> errorResult(
                            "Run run_id=$runId is still in progress, so its artifacts are not ready yet. " +
                                "Use the get_cloud_run_status tool to poll until the run reaches a terminal state " +
                                "(SUCCESS, ERROR, CANCELED, WARNING), then retry describe_cloud_run."
                        )
                        else -> errorResult(
                            "Failed to fetch cloud run (HTTP ${e.statusCode}) for run_id=$runId"
                        )
                    }
                }

                // approach c: drop the whole-run `artifactsArchive` zip; surface only individually-signed
                // artifacts (see `visibleArtifacts`).
                val artifacts = visibleArtifacts(run.artifacts)

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
                        artifacts.forEach { artifact ->
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

    /**
     * v1 (approach c): the whole-run `artifactsArchive` zip (which also carries screenshots) is dropped,
     * leaving only the individually-signed artifacts. Resolving the zip would need a second signed-URL
     * call (approach b) — deferred. Hoisted + tested so the invariant can't silently regress if the
     * filtered type ever drifts from the backend's wire value.
     */
    internal fun visibleArtifacts(artifacts: List<RunArtifact>): List<RunArtifact> =
        artifacts.filterNot { it.type == ARTIFACTS_ARCHIVE_TYPE }

    private const val ARTIFACTS_ARCHIVE_TYPE = "artifactsArchive"

    private fun errorResult(message: String): CallToolResult {
        return CallToolResult(content = listOf(TextContent(message)), isError = true)
    }
}
