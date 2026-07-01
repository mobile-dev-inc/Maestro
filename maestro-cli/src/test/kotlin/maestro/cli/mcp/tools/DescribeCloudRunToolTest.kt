package maestro.cli.mcp.tools

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import maestro.cli.api.RunArtifact
import maestro.cli.api.RunArtifactZip
import maestro.cli.api.RunDeviceSpec
import maestro.cli.api.RunDetails
import org.junit.jupiter.api.Test

class DescribeCloudRunToolTest {

    private fun run(
        artifacts: List<RunArtifact> = emptyList(),
        zips: List<RunArtifactZip> = emptyList(),
        archive: String? = null,
    ) = RunDetails(
        id = "run_1", createdAt = "t", startedAt = null, finishedAt = null,
        status = "FAILURE", failureReason = "TEST_ERROR", resultMessage = null,
        deviceSpec = RunDeviceSpec("IOS", "iPhone 15", "17.5"), totalTimeMs = 1,
        artifacts = artifacts, artifactsZips = zips, artifactsArchiveEndpoint = archive,
    )

    @Test
    fun `surfaces direct files, folder zips, and the whole-run archive by fetch semantics`() {
        val json = Json.parseToJsonElement(
            DescribeCloudRunTool.buildRunJson(
                run(
                    artifacts = listOf(RunArtifact("screenRecording", "mp4", "https://x/r.mp4", 1)),
                    zips = listOf(RunArtifactZip("screenshots", "/v2/runs/run_1/zips/screenshots", 7)),
                    archive = "/v2/runs/run_1/artifacts.zip",
                )
            )
        ).jsonObject

        // Individual files keep a direct `url`.
        assertThat(json["artifacts"]!!.jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content)
            .isEqualTo("https://x/r.mp4")
        // Folder collections surface an `endpoint` + count (two-step), not a url.
        val zip = json["artifacts_zips"]!!.jsonArray[0].jsonObject
        assertThat(zip["endpoint"]!!.jsonPrimitive.content).isEqualTo("/v2/runs/run_1/zips/screenshots")
        assertThat(zip.containsKey("url")).isFalse()
        // Whole-run archive is the two-step endpoint.
        assertThat(json["artifacts_archive_endpoint"]!!.jsonPrimitive.content)
            .isEqualTo("/v2/runs/run_1/artifacts.zip")
    }

    @Test
    fun `omits the archive endpoint and lists nothing when the run produced no artifacts`() {
        val json = Json.parseToJsonElement(DescribeCloudRunTool.buildRunJson(run())).jsonObject
        assertThat(json["artifacts"]!!.jsonArray).isEmpty()
        assertThat(json["artifacts_zips"]!!.jsonArray).isEmpty()
        assertThat(json.containsKey("artifacts_archive_endpoint")).isFalse()
    }
}
