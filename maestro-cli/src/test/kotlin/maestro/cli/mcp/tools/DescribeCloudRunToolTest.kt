package maestro.cli.mcp.tools

import com.google.common.truth.Truth.assertThat
import maestro.cli.api.RunArtifact
import org.junit.jupiter.api.Test

class DescribeCloudRunToolTest {

    @Test
    fun `visibleArtifacts drops the artifactsArchive zip and keeps the rest`() {
        val artifacts = listOf(
            RunArtifact(type = "screenRecording", format = "mp4", url = "https://x/r.mp4", sizeBytes = 1),
            RunArtifact(type = "simulatorLog", format = "txt", url = "https://x/s.log", sizeBytes = 2),
            RunArtifact(type = "artifactsArchive", format = "zip", url = "/v2/runs/run_1/artifacts.zip", sizeBytes = null),
        )

        val visible = DescribeCloudRunTool.visibleArtifacts(artifacts)

        assertThat(visible.map { it.type }).containsExactly("screenRecording", "simulatorLog").inOrder()
    }

    @Test
    fun `visibleArtifacts returns empty for an empty list`() {
        assertThat(DescribeCloudRunTool.visibleArtifacts(emptyList())).isEmpty()
    }
}
