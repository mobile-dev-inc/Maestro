package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.types.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.serialization.json.*
import maestro.auth.ApiKey
import maestro.cli.api.ApiClient
import maestro.cli.util.FileUtils.isZip
import maestro.cli.util.WorkingDirectory
import maestro.cli.view.TestSuiteStatusView
import maestro.orchestra.workspace.WorkspaceUtils
import maestro.utils.TemporaryDirectory
import org.rauschig.jarchivelib.ArchiveFormat
import org.rauschig.jarchivelib.ArchiverFactory
import java.io.ByteArrayInputStream
import kotlin.io.path.absolute

object RunOnCloudTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "run_on_cloud",
                description = "Submit a Maestro flow (or folder of flows) to Maestro Cloud for execution on cloud devices. " +
                    "Returns immediately with an upload_id and dashboard URL. Use get_cloud_run_status to poll for results. " +
                    "Requires Maestro Cloud authentication: run `maestro login` (recommended), or set MAESTRO_CLOUD_API_KEY for non-interactive use.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject {
                        putJsonObject("app_file") {
                            put("type", "string")
                            put("description", "Path to the app binary (.apk, .ipa, or .zip). Absolute or relative to the current working directory.")
                        }
                        putJsonObject("flows") {
                            put("type", "string")
                            put("description", "Path to a single flow file or a folder containing flows. Absolute or relative to the current working directory.")
                        }
                        putJsonObject("name") {
                            put("type", "string")
                            put("description", "Optional human-readable name for this upload.")
                        }
                        putJsonObject("project_id") {
                            put("type", "string")
                            put("description", "Optional Maestro Cloud project ID. If omitted and the account has exactly one project, it is auto-selected; if the account has multiple projects, this parameter is required.")
                        }
                        putJsonObject("env") {
                            put("type", "object")
                            put("description", "Optional map of environment variables to inject into the flows (e.g. {\"APP_ID\": \"com.example.app\"}).")
                            putJsonObject("additionalProperties") {
                                put("type", "string")
                            }
                        }
                        putJsonObject("include_tags") {
                            put("type", "array")
                            put("description", "Only run flows that have any of these tags.")
                            putJsonObject("items") { put("type", "string") }
                        }
                        putJsonObject("exclude_tags") {
                            put("type", "array")
                            put("description", "Skip flows that have any of these tags.")
                            putJsonObject("items") { put("type", "string") }
                        }
                        putJsonObject("device_model") {
                            put("type", "string")
                            put("description", "Cloud device model (e.g. `iPhone-17-Pro`, `pixel_6`). Call `list_cloud_devices` for valid values.")
                        }
                        putJsonObject("device_os") {
                            put("type", "string")
                            put("description", "Cloud device OS version, case-sensitive (e.g. `iOS-17-5`, `android-34`). Call `list_cloud_devices` for valid values.")
                        }
                    },
                    required = listOf("app_file", "flows")
                )
            )
        ) { request ->
            val originalOut = System.out
            val originalIn = System.`in`
            System.setOut(System.err)
            // Redirect stdin to an empty stream so ApiClient.upload's interactive
            // trial-not-started Scanner branch cannot hang waiting for user input
            // that can never arrive (stdin is the MCP JSON-RPC protocol pipe,
            // already captured by the transport before this swap). The MCP
            // transport keeps its own reference to the real System.in, so this
            // does not affect the protocol channel.
            System.setIn(ByteArrayInputStream(ByteArray(0)))
            try {
                val appFileArg = request.arguments?.get("app_file")?.jsonPrimitive?.content
                val flowsArg = request.arguments?.get("flows")?.jsonPrimitive?.content
                val name = request.arguments?.get("name")?.jsonPrimitive?.content
                val projectIdArg = request.arguments?.get("project_id")?.jsonPrimitive?.content
                val envParam = request.arguments?.get("env")?.jsonObject
                val includeTags = request.arguments?.get("include_tags")?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                } ?: emptyList()
                val excludeTags = request.arguments?.get("exclude_tags")?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                } ?: emptyList()
                val deviceModel = request.arguments?.get("device_model")?.jsonPrimitive?.content
                val deviceOs = request.arguments?.get("device_os")?.jsonPrimitive?.content

                if (appFileArg.isNullOrBlank()) {
                    return@RegisteredTool errorResult("app_file is required")
                }
                if (flowsArg.isNullOrBlank()) {
                    return@RegisteredTool errorResult("flows is required")
                }

                val apiKey = ApiKey.getToken()
                if (apiKey.isNullOrBlank()) {
                    return@RegisteredTool errorResult(
                        "Not authenticated with Maestro Cloud. Run `maestro login` in your terminal to authenticate " +
                            "via your browser, then retry this request. For non-interactive setups, set MAESTRO_CLOUD_API_KEY."
                    )
                }

                val appFile = WorkingDirectory.resolve(appFileArg)
                if (!appFile.exists()) {
                    return@RegisteredTool errorResult("App file not found: ${appFile.absolutePath}")
                }
                val flowsFile = WorkingDirectory.resolve(flowsArg)
                if (!flowsFile.exists()) {
                    return@RegisteredTool errorResult("Flows path not found: ${flowsFile.absolutePath}")
                }

                val apiUrl = System.getenv("MAESTRO_CLOUD_API_URL")
                    ?: System.getenv("MAESTRO_API_URL")
                    ?: "https://api.copilot.mobile.dev"
                val client = ApiClient(apiUrl)

                val projectId = if (!projectIdArg.isNullOrBlank()) {
                    projectIdArg
                } else {
                    val projects = try {
                        client.getProjects(apiKey)
                    } catch (e: ApiClient.ApiException) {
                        return@RegisteredTool errorResult(
                            "Failed to list Maestro Cloud projects (HTTP ${e.statusCode}). " +
                                "Project auto-pick requires a session token from `maestro login`; " +
                                "MAESTRO_CLOUD_API_KEY is project-scoped and cannot list projects. " +
                                "Either run `maestro login`, or pass project_id explicitly in this tool call."
                        )
                    } catch (e: Exception) {
                        return@RegisteredTool errorResult("Failed to list Maestro Cloud projects: ${e.message}")
                    }
                    when (projects.size) {
                        0 -> return@RegisteredTool errorResult(
                            "No Maestro Cloud projects found for this account. Create one at https://console.mobile.dev."
                        )
                        1 -> projects[0].id
                        else -> return@RegisteredTool errorResult(
                            "Multiple Maestro Cloud projects found. Pass project_id explicitly. Available: " +
                                projects.joinToString(", ") { "${it.id}:${it.name}" }
                        )
                    }
                }

                val response = TemporaryDirectory.use { tmpDir ->
                    val workspaceZip = tmpDir.resolve("workspace.zip")
                    WorkspaceUtils.createWorkspaceZip(flowsFile.toPath().absolute(), workspaceZip)

                    val appToSend = if (appFile.isZip()) {
                        appFile
                    } else {
                        val archiver = ArchiverFactory.createArchiver(ArchiveFormat.ZIP)
                        @Suppress("RemoveRedundantSpreadOperator")
                        archiver.create(appFile.name + ".zip", tmpDir.toFile(), *arrayOf(appFile.absoluteFile))
                    }

                    client.upload(
                        authToken = apiKey,
                        appFile = appToSend.toPath(),
                        workspaceZip = workspaceZip,
                        uploadName = name,
                        mappingFile = null,
                        repoOwner = null,
                        repoName = null,
                        branch = null,
                        commitSha = null,
                        pullRequestId = null,
                        env = envParam?.mapValues { it.value.jsonPrimitive.content },
                        includeTags = includeTags,
                        excludeTags = excludeTags,
                        disableNotifications = false,
                        projectId = projectId,
                        deviceModel = deviceModel,
                        deviceOs = deviceOs,
                        androidApiLevel = null,
                    )
                }

                val url = TestSuiteStatusView.uploadUrl(projectId, response.appId, response.uploadId, client.domain)

                val result = buildJsonObject {
                    put("success", true)
                    put("upload_id", response.uploadId)
                    put("project_id", projectId)
                    put("app_id", response.appId)
                    response.appBinaryId?.let { put("app_binary_id", it) }
                    put("url", url)
                    put("status", "PENDING")
                    put("message", "Upload submitted. Use get_cloud_run_status with this upload_id and project_id to poll for results.")
                }.toString()

                CallToolResult(content = listOf(TextContent(result)))
            } catch (e: Exception) {
                classifyUploadFailure(e)
            } finally {
                System.setOut(originalOut)
                System.setIn(originalIn)
            }
        }
    }

    private fun errorResult(message: String): CallToolResult {
        return CallToolResult(content = listOf(TextContent(message)), isError = true)
    }

    /**
     * Translate an [Exception] from `client.upload` into a user-facing error.
     *
     * The backend gates uploads on account subscription state and the CLI's
     * `ApiClient.upload` surfaces those as `CliError("Upload request failed
     * (403|402): <message>")` where the message includes phrases like "trial
     * has not started", "trial has expired", or "payment". It also has an
     * interactive Scanner branch for trial-not-started that cannot run under
     * MCP; we redirect stdin to an empty stream beforehand, which makes
     * `scanner.nextLine()` throw `NoSuchElementException`. Both paths land
     * here and should resolve to a single clean message that points the user
     * at `app.maestro.dev` rather than leaking the raw CLI exception text.
     */
    private fun classifyUploadFailure(e: Exception): CallToolResult {
        val message = e.message.orEmpty()
        val looksLikePlanIssue = e is java.util.NoSuchElementException ||
            listOf("trial", "payment", "subscription", "has not started", "has expired")
                .any { message.contains(it, ignoreCase = true) }
        return if (looksLikePlanIssue) {
            errorResult(
                "Your Maestro Cloud account is not ready to run flows on cloud devices. " +
                    "This typically means your free trial has not started (or has expired), " +
                    "or there's a payment issue. Start your free trial or manage your " +
                    "subscription at https://app.maestro.dev, then retry this request."
            )
        } else {
            errorResult("Failed to submit cloud run: ${message.ifBlank { e.javaClass.simpleName }}")
        }
    }
}
