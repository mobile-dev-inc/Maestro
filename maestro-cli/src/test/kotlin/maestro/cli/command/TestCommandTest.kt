package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import maestro.orchestra.WorkspaceConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import picocli.CommandLine
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension
import java.nio.file.Path

@ExtendWith(SystemStubsExtension::class)
class TestCommandTest {

    private lateinit var testCommand: TestCommand

    @SystemStub
    private val environment = EnvironmentVariables()

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
    ************* API key Tests **************
    ******************************************/
    @Test
    fun `--api-key is the key AI commands get, even when MAESTRO_CLOUD_API_KEY is set`() {
        environment.set("MAESTRO_CLOUD_API_KEY", "key-from-env")
        val command = TestCommand()
        CommandLine(command).parseArgs("--api-key", "key-from-flag", "flow.yaml")

        assertThat(command.authToken).isEqualTo("key-from-flag")
    }

    @Test
    fun `MAESTRO_CLOUD_API_KEY is the key AI commands get when --api-key is not passed`() {
        environment.set("MAESTRO_CLOUD_API_KEY", "key-from-env")
        val command = TestCommand()
        CommandLine(command).parseArgs("flow.yaml")

        assertThat(command.authToken).isEqualTo("key-from-env")
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
