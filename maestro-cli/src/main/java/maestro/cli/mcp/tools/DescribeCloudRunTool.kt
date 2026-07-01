package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.auth.ApiKey
import maestro.cli.api.ApiClient
import maestro.cli.api.RunDetails

object DescribeCloudRunTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "describe_cloud_run",
                description = "Fetch metadata and artifacts for a single Maestro Cloud run by its run_id. " +
                    "Returns run status, failure reason, device spec, timing, and artifacts split by how you fetch them: " +
                    "`artifacts` are files with a directly-downloadable signed `url` (screen recording, simulator/xctest/emulator " +
                    "logs, view hierarchy); `artifacts_zips` (e.g. screenshots) and `artifacts_archive_endpoint` (the whole-run " +
                    "zip) are API paths you call with the API key to get a `{ signedUrl }`. " +
                    "IMPORTANT: run_id is the per-flow run id from a dashboard run URL, NOT the upload_id returned by " +
                    "run_on_cloud. Older runs created before run-scoped artifact storage return no artifacts. " +
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

                CallToolResult(content = listOf(TextContent(buildRunJson(run))))
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
     * Serializes the run for the agent, preserving the backend's fetch-semantics split:
     * `artifacts[]` carry a directly-downloadable `url`; `artifacts_zips[]` and
     * `artifacts_archive_endpoint` carry API `endpoint`s that need a second authenticated call
     * returning `{ signedUrl }`. Enum-like values (`status`/`failure_reason`, artifact `type`/`format`)
     * are passed through untouched. Hoisted so the output contract is unit-tested.
     */
    internal fun buildRunJson(run: RunDetails): String = buildJsonObject {
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
        // Direct-download: `url` is a signed blob.
        putJsonArray("artifacts") {
            run.artifacts.forEach { a ->
                addJsonObject {
                    put("type", a.type)
                    put("format", a.format)
                    put("url", a.url)
                    a.sizeBytes?.let { put("size_bytes", it) }
                }
            }
        }
        // Two-step: `endpoint` needs an authenticated call returning `{ signedUrl }` (e.g. screenshots).
        putJsonArray("artifacts_zips") {
            run.artifactsZips.forEach { z ->
                addJsonObject {
                    put("type", z.type)
                    put("endpoint", z.endpoint)
                    put("count", z.count)
                }
            }
        }
        // Two-step whole-run archive; omitted when the run produced no artifacts.
        run.artifactsArchiveEndpoint?.let { put("artifacts_archive_endpoint", it) }
    }.toString()

    private fun errorResult(message: String): CallToolResult {
        return CallToolResult(content = listOf(TextContent(message)), isError = true)
    }
}
