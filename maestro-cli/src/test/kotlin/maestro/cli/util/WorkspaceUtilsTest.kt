package maestro.cli.util

import com.google.common.truth.Truth
import org.apache.commons.io.FileUtils
import org.junit.jupiter.api.Test
import java.nio.file.Path

class WorkspaceUtilsTest {

    private fun flowFiles(): List<Path> {
        val flowA = FileUtils.toFile(this.javaClass.getResource("/tags/flowA.yaml"))
        val flowB = FileUtils.toFile(this.javaClass.getResource("/tags/flowB.yaml"))
        return listOf(flowA.toPath(), flowB.toPath())
    }

    @Test
    fun `Test filterFlowsFilesBasedOnTags only include-tags`() {
        val result = WorkspaceUtils.filterFlowFilesBasedOnTags(
            flowFiles(),
            includeTags = listOf("dev"),
        )

        Truth.assertThat(result).hasSize(2)
        Truth.assertThat(result.first().fileName.toString()).isEqualTo("flowA.yaml")
        Truth.assertThat(result[1].fileName.toString()).isEqualTo("flowB.yaml")
    }

    @Test
    fun `Test filterFlowsFilesBasedOnTags only exclude-tags`() {
        val result = WorkspaceUtils.filterFlowFilesBasedOnTags(
            flowFiles(),
            excludeTags = listOf("pull-request"),
        )

        Truth.assertThat(result).hasSize(1)
        Truth.assertThat(result.first().fileName.toString()).isEqualTo("flowB.yaml")
    }

    @Test
    fun `Test filterFlowsFilesBasedOnTags proving both`() {
        val result = WorkspaceUtils.filterFlowFilesBasedOnTags(
            flowFiles(),
            includeTags = listOf("dev"),
            excludeTags = listOf("pull-request"),
        )

        Truth.assertThat(result).hasSize(1)
        Truth.assertThat(result.first().fileName.toString()).isEqualTo("flowB.yaml")
    }
}