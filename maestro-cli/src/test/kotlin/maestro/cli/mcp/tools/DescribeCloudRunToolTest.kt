package maestro.cli.mcp.tools

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
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
}
