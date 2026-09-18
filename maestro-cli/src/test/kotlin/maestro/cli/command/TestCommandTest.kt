package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import maestro.orchestra.WorkspaceConfig
import maestro.orchestra.StepArtifactConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.assertThrows
import picocli.CommandLine
import java.nio.file.Path

class TestCommandTest {

    private lateinit var testCommand: TestCommand

    @BeforeEach
    fun setUp() {
        testCommand = TestCommand()
    }

    /*****************************************
    *** executionPlanIncludesWebFlow Tests ***
    ******************************************/
    @Test
    fun `executionPlanIncludesWebFlow should return false when both flowsToRun and sequence flows are empty`() {
        val executionPlan = WorkspaceExecutionPlanner.ExecutionPlan(
            flowsToRun = emptyList(),
            sequence = WorkspaceExecutionPlanner.FlowSequence(emptyList(), true),
            workspaceConfig = WorkspaceConfig()
        )
        val result = testCommand.executionPlanIncludesWebFlow(executionPlan)
        assertThat(result).isFalse()
    }

    @Test
    fun `executionPlanIncludesWebFlow should return true when flowsToRun contains both mobile & web flow`() {
        val workspacePath = getTestResourcePath("workspaces/test_command_test/00_mixed_web_mobile_flow_tests")
        val executionPlan = WorkspaceExecutionPlanner.plan(
            input = setOf(workspacePath),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            config = null
        )
        val includesWebFlow = testCommand.executionPlanIncludesWebFlow(executionPlan)
        assertThat(includesWebFlow).isTrue()
    }

    @Test
    fun `executionPlanIncludesWebFlow should return true when sequence flows contains web flow only`() {
        val workspacePath = getTestResourcePath("workspaces/test_command_test/01_web_only")
        val executionPlan = WorkspaceExecutionPlanner.plan(
            input = setOf(workspacePath),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            config = null
        )
        val result = testCommand.executionPlanIncludesWebFlow(executionPlan)
        assertThat(result).isTrue()
    }

    @Test
    fun `executionPlanIncludesWebFlow should return false when no web flows exist`() {
        val workspacePath = getTestResourcePath("workspaces/test_command_test/02_mobile_only")
        val executionPlan = WorkspaceExecutionPlanner.plan(
            input = setOf(workspacePath),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            config = null
        )
        val result = testCommand.executionPlanIncludesWebFlow(executionPlan)
        assertThat(result).isFalse()
    }

    @Test
    fun `executionPlanIncludesWebFlow should return true if after config mixed flows exist`() {
        val workspacePath = getTestResourcePath("workspaces/test_command_test/03_mixed_with_config_execution_order")
        val executionPlan = WorkspaceExecutionPlanner.plan(
            input = setOf(workspacePath),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            config = null
        )
        val result = testCommand.executionPlanIncludesWebFlow(executionPlan)
        assertThat(result).isTrue()
    }

    @Test
    fun `executionPlanIncludesWebFlow should return false if after config no web flows exist`() {
        val workspacePath = getTestResourcePath("workspaces/test_command_test/04_web_only_with_config_execution_order")
        val executionPlan = WorkspaceExecutionPlanner.plan(
            input = setOf(workspacePath),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            config = null
        )
        val result = testCommand.executionPlanIncludesWebFlow(executionPlan)
        assertThat(result).isFalse()
    }

    /*****************************************
    ******** allFlowsAreWebFlow Tests ********
    ******************************************/
    @Test
    fun `allFlowsAreWebFlow should return false when both flowsToRun and sequence flows are empty`() {
        val executionPlan = WorkspaceExecutionPlanner.ExecutionPlan(
            flowsToRun = emptyList(),
            sequence = WorkspaceExecutionPlanner.FlowSequence(emptyList(), true),
            workspaceConfig = WorkspaceConfig()
        )
        val result = testCommand.allFlowsAreWebFlow(executionPlan)
        assertThat(result).isFalse()
    }

    @Test
    fun `allFlowsAreWebFlow should return false when flowsToRun contains both mobile & web flow`() {
        val workspacePath = getTestResourcePath("workspaces/test_command_test/00_mixed_web_mobile_flow_tests")
        val executionPlan = WorkspaceExecutionPlanner.plan(
            input = setOf(workspacePath),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            config = null
        )
       val result = testCommand.allFlowsAreWebFlow(executionPlan)
       assertThat(result).isFalse()
    }

    @Test
    fun `allFlowsAreWebFlow should return true when sequence flows contains web flow only`() {
        val workspacePath = getTestResourcePath("workspaces/test_command_test/01_web_only")
        val executionPlan = WorkspaceExecutionPlanner.plan(
            input = setOf(workspacePath),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            config = null
        )
        val result = testCommand.allFlowsAreWebFlow(executionPlan)
        assertThat(result).isTrue()
    }

    @Test
    fun `allFlowsAreWebFlow should return false when no web flows exist`() {
        val workspacePath = getTestResourcePath("workspaces/test_command_test/02_mobile_only")
        val executionPlan = WorkspaceExecutionPlanner.plan(
            input = setOf(workspacePath),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            config = null
        )
        val result = testCommand.allFlowsAreWebFlow(executionPlan)
        assertThat(result).isFalse()
    }

    @Test
    fun `allFlowsAreWebFlow should return false if after config mixed flows exist`() {
        val workspacePath = getTestResourcePath("workspaces/test_command_test/03_mixed_with_config_execution_order")
        val executionPlan = WorkspaceExecutionPlanner.plan(
            input = setOf(workspacePath),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            config = null
        )
        val result = testCommand.allFlowsAreWebFlow(executionPlan)
        assertThat(result).isFalse()
    }

    @Test
    fun `allFlowsAreWebFlow should return false if after config no web flows exist`() {
        val workspacePath = getTestResourcePath("workspaces/test_command_test/04_web_only_with_config_execution_order")
        val executionPlan = WorkspaceExecutionPlanner.plan(
            input = setOf(workspacePath),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            config = null
        )
        val result = testCommand.executionPlanIncludesWebFlow(executionPlan)
        assertThat(result).isFalse()
    }

    @Test
    fun `analyze turns step screenshots on`() {
        assertThat(
            resolveStepArtifactConfig(
                analyze = true,
                captureAll = false,
                captureScreenshots = false,
                captureHierarchy = false,
            )
        ).isEqualTo(StepArtifactConfig(captureScreenshots = true))
    }

    @Test
    fun `analyze can add hierarchy to its step screenshot`() {
        assertThat(
            resolveStepArtifactConfig(
                analyze = true,
                captureAll = false,
                captureScreenshots = false,
                captureHierarchy = true,
            )
        )
            .isEqualTo(
                StepArtifactConfig(
                    captureScreenshots = true,
                    captureHierarchy = true,
                )
            )
    }

    @Test
    fun `hierarchy capture is independent from screenshot capture`() {
        assertThat(
            resolveStepArtifactConfig(
                analyze = false,
                captureAll = false,
                captureScreenshots = false,
                captureHierarchy = true,
            )
        ).isEqualTo(StepArtifactConfig(captureHierarchy = true))
    }

    @Test
    fun `capture all enables every step artifact`() {
        assertThat(
            resolveStepArtifactConfig(
                analyze = false,
                captureAll = true,
                captureScreenshots = false,
                captureHierarchy = false,
            )
        ).isEqualTo(
            StepArtifactConfig(
                captureScreenshots = true,
                captureHierarchy = true,
            )
        )
    }

    @Test
    fun `sources union rather than override each other`() {
        // Every source can only add. No combination of flags subtracts an
        // artifact another source asked for.
        assertThat(
            resolveStepArtifactConfig(
                analyze = true,
                captureAll = true,
                captureScreenshots = true,
                captureHierarchy = true,
            )
        ).isEqualTo(
            StepArtifactConfig(
                captureScreenshots = true,
                captureHierarchy = true,
            )
        )
    }

    @Test
    fun `nothing requested captures nothing`() {
        assertThat(
            resolveStepArtifactConfig(
                analyze = false,
                captureAll = false,
                captureScreenshots = false,
                captureHierarchy = false,
            )
        ).isEqualTo(StepArtifactConfig())
    }

    @Test
    fun `picocli exposes step artifact switches as plain flags`() {
        val parsed = CommandLine(TestCommand()).parseArgs(
            "--capture-all-step-artifacts",
            "--capture-step-hierarchy",
            "--capture-step-screenshots",
            "flow.yaml",
        )

        assertThat(parsed.matchedOptionValue<Boolean>("--capture-all-step-artifacts", false)).isTrue()
        assertThat(parsed.matchedOptionValue<Boolean>("--capture-step-hierarchy", false)).isTrue()
        assertThat(parsed.matchedOptionValue<Boolean>("--capture-step-screenshots", false)).isTrue()
    }

    @Test
    fun `picocli rejects negated step artifact switches`() {
        // The levers are additive: there is no supported way to subtract an
        // artifact that another source asked for, so no --no- form exists.
        assertThrows<CommandLine.UnmatchedArgumentException> {
            CommandLine(TestCommand()).parseArgs("--no-capture-step-hierarchy", "flow.yaml")
        }
    }

    /*****************************************
    ************ Common Functions ************
    ******************************************/
    private fun getTestResourcePath(resourcePath: String): Path {
        val resourceUrl = javaClass.classLoader.getResource(resourcePath)
        requireNotNull(resourceUrl) { "Test resource not found: $resourcePath" }
        return Path.of(resourceUrl.toURI())
    }
}
