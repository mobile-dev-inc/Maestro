package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import maestro.orchestra.WorkspaceConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
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

    /*****************************************
    *** missingOrderedFlowsWarning Tests ***
    ******************************************/
    @Test
    fun `missingOrderedFlowsWarning should return null when no ordered flows are missing`() {
        val executionPlan = WorkspaceExecutionPlanner.ExecutionPlan(
            flowsToRun = emptyList(),
            sequence = WorkspaceExecutionPlanner.FlowSequence(listOf(Path.of("flowA.yaml")), true),
            workspaceConfig = WorkspaceConfig()
        )
        val result = testCommand.missingOrderedFlowsWarning(executionPlan)
        assertThat(result).isNull()
    }

    @Test
    fun `missingOrderedFlowsWarning should list the missing flows and say the rest still run in sequence`() {
        val executionPlan = WorkspaceExecutionPlanner.ExecutionPlan(
            flowsToRun = emptyList(),
            sequence = WorkspaceExecutionPlanner.FlowSequence(
                flows = listOf(Path.of("flowA.yaml")),
                continueOnFailure = true,
                missingFlows = listOf("flowB", "flowC"),
            ),
            workspaceConfig = WorkspaceConfig()
        )
        val result = testCommand.missingOrderedFlowsWarning(executionPlan)
        assertThat(result).isEqualTo(
            """
            Skipping these flows from executionOrder.flowsOrder, as they are not part of this run:
            - flowB
            - flowC
            The rest of flowsOrder still runs in sequence.
            """.trimIndent()
        )
    }

    @Test
    fun `missingOrderedFlowsWarning should say nothing runs in sequence when every ordered flow is missing`() {
        val executionPlan = WorkspaceExecutionPlanner.ExecutionPlan(
            flowsToRun = listOf(Path.of("flowC.yaml")),
            sequence = WorkspaceExecutionPlanner.FlowSequence(
                flows = emptyList(),
                continueOnFailure = true,
                missingFlows = listOf("flowA", "flowB"),
            ),
            workspaceConfig = WorkspaceConfig()
        )
        val result = testCommand.missingOrderedFlowsWarning(executionPlan)
        assertThat(result).isEqualTo(
            """
            Skipping these flows from executionOrder.flowsOrder, as they are not part of this run:
            - flowA
            - flowB
            No flows from flowsOrder are left, so every flow runs without a set order.
            """.trimIndent()
        )
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
