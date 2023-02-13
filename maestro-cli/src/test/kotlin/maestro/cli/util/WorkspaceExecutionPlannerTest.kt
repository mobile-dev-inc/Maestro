package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

internal class WorkspaceExecutionPlannerTest {

    @Test
    internal fun `000 - Individual file`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = path("/workspaces/000_individual_file/flow.yaml"),
            includeTags = listOf(),
            excludeTags = listOf(),
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/000_individual_file/flow.yaml"),
        )
    }

    @Test
    internal fun `001 - Simple workspace`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = path("/workspaces/001_simple"),
            includeTags = listOf(),
            excludeTags = listOf(),
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/001_simple/flowA.yaml"),
            path("/workspaces/001_simple/flowB.yaml"),
        )
    }

    @Test
    internal fun `002 - Workspace with subflows`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = path("/workspaces/002_subflows"),
            includeTags = listOf(),
            excludeTags = listOf(),
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/002_subflows/flowA.yaml"),
            path("/workspaces/002_subflows/flowB.yaml"),
        )
    }

    @Test
    internal fun `003 - Include tags`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = path("/workspaces/003_include_tags"),
            includeTags = listOf("included"),
            excludeTags = listOf(),
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/003_include_tags/flowA.yaml"),
        )
    }

    @Test
    internal fun `004 - Exclude tags`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = path("/workspaces/004_exclude_tags"),
            includeTags = listOf(),
            excludeTags = listOf("excluded"),
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/004_exclude_tags/flowA.yaml"),
            path("/workspaces/004_exclude_tags/flowC.yaml"),
        )
    }

    @Test
    internal fun `005 - Custom include pattern`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = path("/workspaces/005_custom_include_pattern"),
            includeTags = listOf(),
            excludeTags = listOf(),
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/005_custom_include_pattern/featureA/flowA.yaml"),
            path("/workspaces/005_custom_include_pattern/featureB/flowB.yaml"),
        )
    }

    @Test
    internal fun `006 - Include subfolders`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = path("/workspaces/006_include_subfolders"),
            includeTags = listOf(),
            excludeTags = listOf(),
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/006_include_subfolders/featureA/flowA.yaml"),
            path("/workspaces/006_include_subfolders/featureB/flowB.yaml"),
            path("/workspaces/006_include_subfolders/featureC/subfolder/flowC.yaml"),
            path("/workspaces/006_include_subfolders/flowD.yaml"),
        )
    }

    @Test
    internal fun `007 - Empty config`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = path("/workspaces/007_empty_config"),
            includeTags = listOf(),
            excludeTags = listOf(),
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/007_empty_config/flowA.yaml"),
            path("/workspaces/007_empty_config/flowB.yaml"),
        )
    }

    @Test
    internal fun `008 - Literal pattern`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = path("/workspaces/008_literal_pattern"),
            includeTags = listOf(),
            excludeTags = listOf(),
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/008_literal_pattern/featureA/flowA.yaml"),
        )
    }

    @Test
    internal fun `009 - Custom fields in config`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = path("/workspaces/009_custom_config_fields"),
            includeTags = listOf(),
            excludeTags = listOf(),
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/009_custom_config_fields/flowA.yaml"),
            path("/workspaces/009_custom_config_fields/flowB.yaml"),
        )
    }

    private fun path(pathStr: String): Path {
        return Paths.get(WorkspaceExecutionPlannerTest::class.java.getResource(pathStr).toURI())
    }

}