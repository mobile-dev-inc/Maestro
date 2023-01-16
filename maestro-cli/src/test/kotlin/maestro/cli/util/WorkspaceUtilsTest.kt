package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import org.apache.commons.io.FileUtils
import org.junit.jupiter.api.Test
import java.nio.file.Path

class WorkspaceUtilsTest {

    private fun flowFiles(): List<Path> {
        val flowA = FileUtils.toFile(this.javaClass.getResource("/tags/flowA.yaml"))
        val flowB = FileUtils.toFile(this.javaClass.getResource("/tags/flowB.yaml"))
        val subFlow = FileUtils.toFile(this.javaClass.getResource("/tags/subflow.yaml"))
        val subSubFlow = FileUtils.toFile(this.javaClass.getResource("/tags/subsubflow.yaml"))
        val config = FileUtils.toFile(this.javaClass.getResource("/tags/config.yml"))
        val script = FileUtils.toFile(this.javaClass.getResource("/tags/script.js"))

        return listOf(
            flowA.toPath(),
            flowB.toPath(),
            subFlow.toPath(),
            subSubFlow.toPath(),
            config.toPath(),
            script.toPath(),
        )
    }

    @Test
    fun `Test filterFilesBasedOnTags only include-tags with one option`() {
        // When
        val result = WorkspaceUtils.filterFilesBasedOnTags(
            flowFiles(),
            includeTags = listOf("dev"),
        )

        // Then
        assertThat(result.filenames()).containsExactly(
            "flowA.yaml",
            "flowB.yaml",
            "subflow.yaml",
            "subsubflow.yaml",
            "config.yml",
            "script.js",
        )
    }

    @Test
    fun `Test filterFilesBasedOnTags only include-tags, but with multiple options`() {
        // When
        val result = WorkspaceUtils.filterFilesBasedOnTags(
            flowFiles(),
            includeTags = listOf("dev", "pull-request"),
        )

        // Then
        assertThat(result.filenames()).containsExactly(
            "flowA.yaml",
            "flowB.yaml",
            "subflow.yaml",
            "subsubflow.yaml",
            "config.yml",
            "script.js",
        )
    }

    @Test
    fun `Test filterFilesBasedOnTags only exclude-tags`() {
        // When
        val result = WorkspaceUtils.filterFilesBasedOnTags(
            flowFiles(),
            excludeTags = listOf("pull-request"),
        )

        // Then
        assertThat(result.filenames()).containsExactly(
            "flowB.yaml",
            "subflow.yaml",
            "subsubflow.yaml",
            "config.yml",
            "script.js",
        )
    }

    @Test
    fun `Test filterFilesBasedOnTags proving both`() {
        // When
        val result = WorkspaceUtils.filterFilesBasedOnTags(
            flowFiles(),
            includeTags = listOf("dev"),
            excludeTags = listOf("pull-request"),
        )

        // Then
        assertThat(result.filenames()).containsExactly(
            "flowB.yaml",
            "subflow.yaml",
            "subsubflow.yaml",
            "config.yml",
            "script.js",
        )
    }

    private fun List<Path>.filenames() = this.map { it.fileName.toString() }

}