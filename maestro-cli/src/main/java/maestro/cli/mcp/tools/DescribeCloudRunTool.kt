package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.auth.ApiKey
import maestro.cli.api.ApiClient
import maestro.cli.api.RunDetails

object DescribeCloudRunTool {
    fun create(): RegisteredTool = RegisteredTool(
        Tool(
            name = "describe_cloud_run",
            description = "Fetch metadata and artifacts for a single Maestro Cloud run by its run_id. " +
                "Returns run status, failure reason, device spec, timing, and `artifacts` — the run's individually-" +
                "stored files, each with a directly-downloadable signed `url` (e.g. screen recording and device logs). " +
                "Set `include_archive` to additionally get `artifactsArchive`: a single zip of the ENTIRE run — " +
                "everything, including the screenshots and view hierarchy that are not in the individual `artifacts` " +
                "list — as a direct url; omit it for a faster response. " +
                "When you present the result, make both options clear to the user: they can download the individual " +
                "files listed in `artifacts` (recording, logs), or the complete archive — call again with " +
                "include_archive=true — for everything including screenshots and the view hierarchy. " +
                "IMPORTANT: run_id is the per-flow run id from a dashboard run URL, NOT the upload_id returned by " +
                "run_on_cloud. Older runs created before run-scoped artifact storage return no artifacts. " +
                "Requires Maestro Cloud authentication: run `maestro login` (recommended), or set MAESTRO_CLOUD_API_KEY for non-interactive use.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("run_id") {
                        put("type", "string")
                        put("description", "The per-flow run id from a dashboard run URL (NOT the upload_id from run_on_cloud).")
                    }
                    putJsonObject("include_archive") {
                        put("type", "boolean")
                        put("description", "When true, also build and include the whole-run zip (bundles everything the run produced, incl. the screenshots and view hierarchy that aren't in the individual `artifacts` list) as an `artifactsArchive` artifact. Slower to build; defaults to false.")
                    }
                },
                required = listOf("run_id")
            )
        )
    ) { request -> handle(request) }

    /** Tool logic, split from the definition above (mirrors RunTool) so `create()` reads as pure wiring and this is testable. */
    internal fun handle(request: CallToolRequest): CallToolResult {
        val originalOut = System.out
        System.setOut(System.err)
        try {
            val runId = request.arguments?.get("run_id")?.jsonPrimitive?.content
            if (runId.isNullOrBlank()) return errorResult("run_id is required")

            val apiKey = ApiKey.getToken()
            if (apiKey.isNullOrBlank()) return errorResult(NOT_AUTHENTICATED_MESSAGE)

            val includeArchive = request.arguments?.get("include_archive")?.jsonPrimitive?.booleanOrNull ?: false

            val apiUrl = System.getenv("MAESTRO_CLOUD_API_URL")
                ?: System.getenv("MAESTRO_API_URL")
                ?: "https://api.copilot.mobile.dev"
            val client = ApiClient(apiUrl)

            val run = try {
                client.describeRun(apiKey, runId, includeArchive)
            } catch (e: ApiClient.ApiException) {
                return errorResult(errorMessageForStatus(e.statusCode, runId))
            }
            return CallToolResult(content = listOf(TextContent(buildRunJson(run))))
        } catch (e: Exception) {
            return CallToolResult(
                content = listOf(TextContent("Failed to describe cloud run: ${e.message ?: e.javaClass.simpleName}")),
                isError = true,
            )
        } finally {
            System.setOut(originalOut)
        }
    }

    /**
     * Serializes the run for the agent. Every `artifacts[].url` is a directly-downloadable signed blob
     * (the whole-run `artifactsArchive` zip is included here too when the caller opted in via
     * `include_archive`). Enum-like values (`status`/`failure_reason`, artifact `type`/`format`) are
     * passed through untouched. Hoisted so the output contract is unit-tested.
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
    }.toString()

    /** Maps an ApiClient failure to a distinct, actionable message for the agent (null status = network/IO). */
    internal fun errorMessageForStatus(statusCode: Int?, runId: String): String = when (statusCode) {
        null -> "Could not reach Maestro Cloud to describe run_id=$runId. Check your network connection and retry."
        401 -> NOT_AUTHENTICATED_MESSAGE
        404 -> "No run found with run_id=$runId. Check the run_id (it is the per-flow run id from a dashboard " +
            "run URL, not the upload_id from run_on_cloud) and that it belongs to your organization."
        409 -> "Run run_id=$runId is still in progress, so its artifacts are not ready yet. " +
            "Use the get_cloud_run_status tool to poll until the run reaches a terminal state " +
            "(SUCCESS, ERROR, CANCELED, WARNING), then retry describe_cloud_run."
        else -> "Failed to fetch cloud run (HTTP $statusCode) for run_id=$runId"
    }

    private const val NOT_AUTHENTICATED_MESSAGE =
        "Not authenticated with Maestro Cloud. Run `maestro login` in your terminal to authenticate " +
            "via your browser, then retry this request. For non-interactive setups, set MAESTRO_CLOUD_API_KEY."

    private fun errorResult(message: String): CallToolResult {
        return CallToolResult(content = listOf(TextContent(message)), isError = true)
    }
}
