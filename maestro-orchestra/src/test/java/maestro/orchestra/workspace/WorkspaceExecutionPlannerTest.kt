package maestro.orchestra.workspace

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.WorkspaceConfig.*
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

internal class WorkspaceExecutionPlannerTest {

    @Test
    internal fun `000 - Individual file`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = paths("/workspaces/000_individual_file/flow.yaml"),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
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
            input = paths("/workspaces/001_simple"),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/001_simple/flowA.yaml"),
            path("/workspaces/001_simple/flowB.yaml"),
        )
    }

    @Test
    internal fun `001 - Multiple files`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = paths(
                "/workspaces/001_simple/flowA.yaml",
                "/workspaces/001_simple/flowB.yaml"
            ),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
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
            input = paths("/workspaces/002_subflows"),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/002_subflows/flowA.yaml"),
            path("/workspaces/002_subflows/flowB.yaml"),
        )
    }

    @Test
    internal fun `002 - Multiple folders`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = paths(
                "/workspaces/001_simple",
                "/workspaces/002_subflows"
            ),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/001_simple/flowA.yaml"),
            path("/workspaces/001_simple/flowB.yaml"),
            path("/workspaces/002_subflows/flowA.yaml"),
            path("/workspaces/002_subflows/flowB.yaml"),
        )
    }

    @Test
    internal fun `002 - Multiple files and folders`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = paths(
                "/workspaces/000_individual_file/flow.yaml",
                "/workspaces/001_simple",
                "/workspaces/002_subflows",
                "/workspaces/003_include_tags/flowC.yaml",
            ),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/000_individual_file/flow.yaml"),
            path("/workspaces/001_simple/flowA.yaml"),
            path("/workspaces/001_simple/flowB.yaml"),
            path("/workspaces/002_subflows/flowA.yaml"),
            path("/workspaces/002_subflows/flowB.yaml"),
            path("/workspaces/003_include_tags/flowC.yaml"),
        )
    }

    @Test
    internal fun `003 - Include tags`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = paths("/workspaces/003_include_tags"),
            includeTags = listOf("included"),
            excludeTags = listOf(),
            config = null,
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
            input = paths("/workspaces/004_exclude_tags"),
            includeTags = listOf(),
            excludeTags = listOf("excluded"),
            config = null,
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
            input = paths("/workspaces/005_custom_include_pattern"),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
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
            input = paths("/workspaces/006_include_subfolders"),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
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
            input = paths("/workspaces/007_empty_config"),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
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
            input = paths("/workspaces/008_literal_pattern"),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
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
            input = paths("/workspaces/009_custom_config_fields"),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/009_custom_config_fields/flowA.yaml"),
            path("/workspaces/009_custom_config_fields/flowB.yaml"),
        )
    }

    @Test
    internal fun `010 - Global include tags`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = paths("/workspaces/010_global_include_tags"),
            includeTags = listOf("featureB"),
            excludeTags = listOf(),
            config = null,
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/010_global_include_tags/flowA.yaml"),
            path("/workspaces/010_global_include_tags/flowA_subflow.yaml"),
            path("/workspaces/010_global_include_tags/flowB.yaml"),
        )
    }

    @Test
    internal fun `011 - Global exclude tags`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = paths("/workspaces/011_global_exclude_tags"),
            includeTags = listOf(),
            excludeTags = listOf("featureA"),
            config = null,
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/011_global_exclude_tags/flowB.yaml"),
            path("/workspaces/011_global_exclude_tags/flowC.yaml"),
            path("/workspaces/011_global_exclude_tags/flowE.yaml"),
        )
    }

    @Test
    internal fun `012 - Deterministic order for local tests`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = paths("/workspaces/012_local_deterministic_order"),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/012_local_deterministic_order/flowA.yaml"),
            path("/workspaces/012_local_deterministic_order/flowB.yaml"),
            path("/workspaces/012_local_deterministic_order/flowC.yaml"),
        ).inOrder()
    }

    @Test
    internal fun `013 - Execution order is respected`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = paths("/workspaces/013_execution_order"),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = null,
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/013_execution_order/flowA.yaml"),
        )

        // Then
        assertThat(plan.sequence).isNotNull()
        assertThat(plan.sequence.flows).containsExactly(
            path("/workspaces/013_execution_order/flowB.yaml"),
            path("/workspaces/013_execution_order/flowCWithCustomName.yaml"),
            path("/workspaces/013_execution_order/flowD.yaml"),
        ).inOrder()
    }

    @Test
    internal fun `014 - Config not null`() {
        // When
        val plan = WorkspaceExecutionPlanner.plan(
            input = paths("/workspaces/014_config_not_null"),
            includeTags = listOf(),
            excludeTags = listOf(),
            config = path("/workspaces/014_config_not_null/config/another_config.yaml"),
        )

        // Then
        assertThat(plan.flowsToRun).containsExactly(
            path("/workspaces/014_config_not_null/flowA.yaml"),
        )
    }

    @Test
    internal fun `017 - Upload configs on local and cloud both are supported`() {
        // when
        val plan = WorkspaceExecutionPlanner.plan(
            input = paths("/workspaces/015_workspace_cloud_configs"),
            includeTags = listOf("included"),
            excludeTags = listOf("notIncluded"),
            config = null
        )

        assertThat(plan.workspaceConfig.notifications?.email?.recipients).containsExactly("abc@mobile.dev")
        assertThat(plan.workspaceConfig.notifications?.slack?.channels).containsExactly("e2e-testing")
        assertThat(plan.workspaceConfig.executionOrder?.flowsOrder).containsExactly("flowA", "flowB")
        assertThat(plan.workspaceConfig.disableRetries).isTrue()
    }

    @Test
    internal fun `017 - Upload platform configs on are supported`() {
        // when
        val plan = WorkspaceExecutionPlanner.plan(
            input = paths("/workspaces/015_workspace_cloud_configs"),
            includeTags = listOf("included"),
            excludeTags = listOf("notIncluded"),
            config = null
        )

        val platformConfiguration = plan.workspaceConfig.platform
        assertThat(platformConfiguration).isEqualTo(
            PlatformConfiguration(
                android = PlatformConfiguration.AndroidConfiguration(disableAnimations = true),
                ios = PlatformConfiguration.IOSConfiguration(disableAnimations = true, snapshotKeyHonorModalViews = false)
            )
        )
    }

    private fun path(path: String): Path? {
        val clazz = WorkspaceExecutionPlannerTest::class.java
        val resource = clazz.getResource(path)?.toURI()
        return resource?.let { Paths.get(it) }
    }

    private fun paths(vararg paths: String): Set<Path> = paths.mapNotNull(::path).toSet()
}
