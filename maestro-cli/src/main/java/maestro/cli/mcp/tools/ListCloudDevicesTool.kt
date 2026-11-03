package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import maestro.cli.api.ApiClient

// Resolves the same way `run_on_cloud` does so both tools hit the same host in one
// session; `EnvUtils.BASE_API_URL` alone ignores `MAESTRO_CLOUD_API_URL`.
private fun cloudApiUrl(): String =
    System.getenv("MAESTRO_CLOUD_API_URL")
        ?: System.getenv("MAESTRO_API_URL")
        ?: "https://api.copilot.mobile.dev"

object ListCloudDevicesTool {
    fun create(): RegisteredTool {
        return RegisteredTool(
            Tool(
                name = "list_cloud_devices",
                description = "List device models and OS versions available on Maestro Cloud. " +
                    "Call this before `run_on_cloud` to discover valid `device_model` / `device_os` pairs. " +
                    "OS versions are returned in the exact case the cloud API expects (e.g. `iOS-17-5`, `android-34`). " +
                    "`defaults` is what a run gets when no device is given. Avoid anything in `deprecated_os` or " +
                    "with a `removal_date`: it stops working on that date.",
                inputSchema = ToolSchema(
                    properties = buildJsonObject { },
                    required = emptyList()
                )
            )
        ) { _ ->
            try {
                val cloudDevices = ApiClient(cloudApiUrl()).listCloudDevices()

                val devices = buildJsonArray {
                    cloudDevices.forEach { (platform, section) ->
                        section.devices.forEach { device ->
                            addJsonObject {
                                put("platform", platform)
                                put("model", device.model)
                                putJsonArray("supported_os") {
                                    device.osVersions.forEach { add(JsonPrimitive(it.id)) }
                                }
                                val deprecatedOs = device.osVersions.filter { it.deprecation != null }
                                if (deprecatedOs.isNotEmpty()) {
                                    putJsonObject("deprecated_os") {
                                        deprecatedOs.forEach { os -> os.deprecation?.let { put(os.id, it.removalDate) } }
                                    }
                                }
                                device.deprecation?.let { put("removal_date", it.removalDate) }
                            }
                        }
                    }
                }

                val result = buildJsonObject {
                    put("devices", devices)
                    val defaults = cloudDevices.mapNotNull { (platform, section) -> section.default?.let { platform to it } }
                    if (defaults.isNotEmpty()) {
                        putJsonObject("defaults") {
                            defaults.forEach { (platform, default) ->
                                putJsonObject(platform) {
                                    put("model", default.model)
                                    put("os", default.os)
                                }
                            }
                        }
                    }
                }

                CallToolResult(content = listOf(TextContent(result.toString())))
            } catch (e: ApiClient.ApiException) {
                val detail = e.statusCode?.let { "HTTP $it" } ?: "network error"
                CallToolResult(
                    content = listOf(TextContent("Failed to list cloud devices ($detail). Check your network connection and retry.")),
                    isError = true,
                )
            } catch (e: Exception) {
                CallToolResult(
                    content = listOf(TextContent("Failed to list cloud devices: ${e.message ?: e.javaClass.simpleName}")),
                    isError = true,
                )
            }
        }
    }
}
