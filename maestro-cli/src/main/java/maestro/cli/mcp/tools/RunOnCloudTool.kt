package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
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
import kotlin.io.path.absolute

object RunOnCloudTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "run_on_cloud",
                description = "Submit a Maestro flow (or folder of flows) to Maestro Cloud for execution on cloud devices. " +
                    "Returns immediately with an upload_id and dashboard URL. Use get_cloud_run_status to poll for results. " +
                    "Requires MAESTRO_CLOUD_API_KEY.",
                inputSchema = Tool.Input(
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
                        putJsonObject("device_os") {
                            put("type", "string")
                            put("description", "Cloud device OS target (e.g. 'ios-17-5', 'android-34'). See the output of `maestro list-cloud-devices` for valid values.")
                        }
                    },
                    required = listOf("app_file", "flows")
                )
            )
        ) { request ->
            val originalOut = System.out
            System.setOut(System.err)
            try {
                val appFileArg = request.arguments["app_file"]?.jsonPrimitive?.content
                val flowsArg = request.arguments["flows"]?.jsonPrimitive?.content
                val name = request.arguments["name"]?.jsonPrimitive?.content
                val projectIdArg = request.arguments["project_id"]?.jsonPrimitive?.content
                val envParam = request.arguments["env"]?.jsonObject
                val includeTags = request.arguments["include_tags"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                } ?: emptyList()
                val excludeTags = request.arguments["exclude_tags"]?.jsonArray?.mapNotNull {
                    it.jsonPrimitive.contentOrNull
                } ?: emptyList()
                val deviceOs = request.arguments["device_os"]?.jsonPrimitive?.content

                if (appFileArg.isNullOrBlank()) {
                    return@RegisteredTool errorResult("app_file is required")
                }
                if (flowsArg.isNullOrBlank()) {
                    return@RegisteredTool errorResult("flows is required")
                }

                val apiKey = ApiKey.getToken()
                if (apiKey.isNullOrBlank()) {
                    return@RegisteredTool errorResult(
                        "MAESTRO_CLOUD_API_KEY environment variable is required. " +
                            "Set it via your MCP client config (env block) or export it in the shell running the MCP server. " +
                            "See https://docs.maestro.dev/api-reference/configuration/workspace-configuration for details."
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
                                "Check that MAESTRO_CLOUD_API_KEY is valid."
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
                CallToolResult(
                    content = listOf(TextContent("Failed to submit cloud run: ${e.message ?: e.javaClass.simpleName}")),
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
