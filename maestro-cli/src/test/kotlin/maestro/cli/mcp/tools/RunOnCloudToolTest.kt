package maestro.cli.mcp.tools

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class RunOnCloudToolTest {

    // Priority is checked before auth and before the files are resolved, so these never reach the network.
    private fun handle(priority: JsonElement): String {
        val request = CallToolRequest(
            CallToolRequestParams(
                name = "run_on_cloud",
                arguments = buildJsonObject {
                    put("app_file", "missing.apk")
                    put("flows", "missing.yaml")
                    put("project_id", "proj_test")
                    put("priority", priority)
                },
            )
        )
        val result = runBlocking { RunOnCloudTool.create().handler(mockk<ClientConnection>(), request) }
        assertThat(result.isError).isTrue()
        return (result.content.single() as TextContent).text
    }

    @Test
    fun `an unknown priority is rejected with the accepted values`() {
        assertThat(handle(JsonPrimitive("urgent"))).isEqualTo("Unknown priority 'urgent'. Use one of: low, normal, high.")
    }

    @Test
    fun `priority in any case, or an explicit null, passes validation`() {
        listOf(JsonPrimitive("low"), JsonPrimitive("NORMAL"), JsonPrimitive("High"), JsonNull).forEach {
            assertThat(handle(it)).doesNotContain("Unknown priority")
        }
    }
}
