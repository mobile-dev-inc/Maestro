package maestro.cli.mcp.tools

import com.google.common.truth.Truth.assertThat
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import maestro.cli.api.RunArtifact
import maestro.cli.api.RunDeviceSpec
import maestro.cli.api.RunDetails
import org.junit.jupiter.api.Test

class DescribeCloudRunToolTest {

    private fun run(artifacts: List<RunArtifact> = emptyList()) = RunDetails(
        id = "run_1", createdAt = "t", startedAt = null, finishedAt = null,
        status = "FAILURE", failureReason = "TEST_ERROR", resultMessage = null,
        deviceSpec = RunDeviceSpec("IOS", "iPhone 15", "17.5"), totalTimeMs = 1,
        artifacts = artifacts,
    )

    @Test
    fun `surfaces artifacts as a flat list of direct urls including the archive when present`() {
        val json = Json.parseToJsonElement(
            DescribeCloudRunTool.buildRunJson(
                run(
                    listOf(
                        RunArtifact("screenRecording", "mp4", "https://x/r.mp4", 1),
                        RunArtifact("artifactsArchive", "zip", "https://x/all.zip", null),
                    )
                )
            )
        ).jsonObject

        val artifacts = json["artifacts"]!!.jsonArray
        assertThat(artifacts.map { it.jsonObject["type"]!!.jsonPrimitive.content })
            .containsExactly("screenRecording", "artifactsArchive").inOrder()
        // Every artifact carries a directly-downloadable `url` — no `endpoint`, no two-step.
        artifacts.forEach { a ->
            assertThat(a.jsonObject["url"]!!.jsonPrimitive.content).startsWith("https://")
            assertThat(a.jsonObject.containsKey("endpoint")).isFalse()
        }
    }

    @Test
    fun `lists nothing when the run produced no artifacts`() {
        val json = Json.parseToJsonElement(DescribeCloudRunTool.buildRunJson(run())).jsonObject
        assertThat(json["artifacts"]!!.jsonArray).isEmpty()
    }

    @Test
    fun `errorMessageForStatus gives a distinct actionable message per status`() {
        // 409 must give an always-followable action (retry when finished), because get_cloud_run_status
        // needs upload_id+project_id the agent can't derive from a run_id — so that path is only conditional.
        assertThat(DescribeCloudRunTool.errorMessageForStatus(409, "run_1")).contains("Retry once the run finishes")
        assertThat(DescribeCloudRunTool.errorMessageForStatus(409, "run_1")).contains("get_cloud_run_status")
        // 404 reminds the caller that run_id is not the run_on_cloud upload_id.
        assertThat(DescribeCloudRunTool.errorMessageForStatus(404, "run_1")).contains("upload_id")
        // null (network/IO) is a connectivity message.
        assertThat(DescribeCloudRunTool.errorMessageForStatus(null, "run_1")).contains("network")
    }

    @Test
    fun `handle rejects a missing run_id`() {
        val result = DescribeCloudRunTool.handle(
            CallToolRequest(CallToolRequestParams(name = "describe_cloud_run", arguments = buildJsonObject {}))
        )
        assertThat(result.isError).isTrue()
        assertThat((result.content.single() as TextContent).text).contains("run_id is required")
    }
}
