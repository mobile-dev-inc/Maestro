package maestro.cli.runner.resultview

import com.google.common.truth.Truth.assertThat
import maestro.cli.runner.CommandState
import maestro.cli.runner.CommandStatus
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.MaestroCommand
import maestro.orchestra.RunFlowCommand
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class PlainTextResultViewTest {

    private lateinit var outputStream: ByteArrayOutputStream
    private lateinit var originalOut: PrintStream

    @BeforeEach
    fun setUp() {
        outputStream = ByteArrayOutputStream()
        originalOut = System.out
        System.setOut(PrintStream(outputStream))
    }

    private fun tearDown() {
        System.setOut(originalOut)
    }

    private fun getOutput(): String {
        return outputStream.toString()
    }

    /**
     * This test verifies that deeply nested runFlow commands are all printed correctly.
     *
     * Bug description: When using nested complex runFlow inside runFlow (and even deeper),
     * the --no-ansi option fails to keep track and stops printing. It gets out of sync.
     *
     * Example structure that was failing:
     * main.yml:
     *   - runFlow: open_app.yml        <- printed fine
     *   - runFlow: login_to_app.yml    <- printed fine
     *   - runFlow: tests.yml           <- this flow and everything in it were NOT being printed
     *
     * Where tests.yml contains:
     *   - runFlow: test1.yml
     *   - runFlow: test2.yml
     *
     * And login_to_app.yml contains a conditional runFlow.
     *
     * The fix ensures unique keys are generated for each nested command by using
     * hierarchical prefixes (e.g., "main:0:sub:0:sub:0") instead of flat indices.
     */
    @Test
    fun `nested runFlow commands should all be printed correctly`() {
        // Given
        val resultView = PlainTextResultView()

        // Create a deeply nested structure similar to the bug scenario:
        // main flow -> runFlow (tests.yml) -> runFlow (test1.yml) -> assertVisible

        val deepestCommand = MaestroCommand(
            assertConditionCommand = AssertConditionCommand(
                condition = Condition(visible = ElementSelector(textRegex = "hello"))
            )
        )

        val deepestCommandState = CommandState(
            status = CommandStatus.COMPLETED,
            command = deepestCommand,
            subOnStartCommands = null,
            subOnCompleteCommands = null,
            subCommands = null
        )

        // test1.yml - contains assertVisible
        val test1RunFlow = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(deepestCommand),
                sourceDescription = "test1.yml",
                config = null
            )
        )

        val test1State = CommandState(
            status = CommandStatus.COMPLETED,
            command = test1RunFlow,
            subOnStartCommands = null,
            subOnCompleteCommands = null,
            subCommands = listOf(deepestCommandState)
        )

        // test2.yml - another nested flow
        val test2RunFlow = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(deepestCommand),
                sourceDescription = "test2.yml",
                config = null
            )
        )

        val test2State = CommandState(
            status = CommandStatus.COMPLETED,
            command = test2RunFlow,
            subOnStartCommands = null,
            subOnCompleteCommands = null,
            subCommands = listOf(deepestCommandState.copy())
        )

        // tests.yml - contains test1 and test2
        val testsRunFlow = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(test1RunFlow, test2RunFlow),
                sourceDescription = "tests.yml",
                config = null
            )
        )

        val testsState = CommandState(
            status = CommandStatus.COMPLETED,
            command = testsRunFlow,
            subOnStartCommands = null,
            subOnCompleteCommands = null,
            subCommands = listOf(test1State, test2State)
        )

        // open_app.yml - simple flow
        val openAppRunFlow = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(deepestCommand),
                sourceDescription = "open_app.yml",
                config = null
            )
        )

        val openAppState = CommandState(
            status = CommandStatus.COMPLETED,
            command = openAppRunFlow,
            subOnStartCommands = null,
            subOnCompleteCommands = null,
            subCommands = listOf(deepestCommandState.copy())
        )

        // login_to_app.yml - contains a conditional runFlow
        val conditionalRunFlow = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(deepestCommand),
                condition = Condition(visible = ElementSelector(textRegex = "name@example.com")),
                sourceDescription = null,
                config = null
            )
        )

        val conditionalState = CommandState(
            status = CommandStatus.COMPLETED,
            command = conditionalRunFlow,
            subOnStartCommands = null,
            subOnCompleteCommands = null,
            subCommands = listOf(deepestCommandState.copy())
        )

        val loginRunFlow = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(conditionalRunFlow),
                sourceDescription = "login_to_app.yml",
                config = null
            )
        )

        val loginState = CommandState(
            status = CommandStatus.COMPLETED,
            command = loginRunFlow,
            subOnStartCommands = null,
            subOnCompleteCommands = null,
            subCommands = listOf(conditionalState)
        )

        // Main flow state with all commands
        val state = UiState.Running(
            flowName = "main.yml",
            commands = listOf(
                openAppState,
                loginState,
                testsState  // This was the problematic flow that wasn't being printed
            )
        )

        // When
        resultView.setState(state)

        // Then
        val output = getOutput()
        tearDown()

        // Verify all nested flows are printed
        assertThat(output).contains("Run open_app.yml")
        assertThat(output).contains("Run login_to_app.yml")
        assertThat(output).contains("Run tests.yml")  // This was missing before the fix
        assertThat(output).contains("Run test1.yml")  // Nested inside tests.yml
        assertThat(output).contains("Run test2.yml")  // Nested inside tests.yml

        // Verify the deepest commands are also printed (assertVisible)
        // Count occurrences - we should have multiple "Assert that" for each nested flow
        val assertCount = output.split("Assert that").size - 1
        assertThat(assertCount).isAtLeast(3)  // At least 3 assertVisible commands should be printed
    }

    @Test
    fun `multiple calls with same nested structure should not duplicate output`() {
        // Given
        val resultView = PlainTextResultView()

        val command = MaestroCommand(
            assertConditionCommand = AssertConditionCommand(
                condition = Condition(visible = ElementSelector(textRegex = "hello"))
            )
        )

        val commandState = CommandState(
            status = CommandStatus.COMPLETED,
            command = command,
            subOnStartCommands = null,
            subOnCompleteCommands = null,
            subCommands = null
        )

        val runFlowCommand = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(command),
                sourceDescription = "test.yml",
                config = null
            )
        )

        val runFlowState = CommandState(
            status = CommandStatus.COMPLETED,
            command = runFlowCommand,
            subOnStartCommands = null,
            subOnCompleteCommands = null,
            subCommands = listOf(commandState)
        )

        val state = UiState.Running(
            flowName = "main.yml",
            commands = listOf(runFlowState)
        )

        // When - call setState multiple times (simulating UI updates)
        resultView.setState(state)
        resultView.setState(state)
        resultView.setState(state)

        // Then
        val output = getOutput()
        tearDown()

        // Should only print once despite multiple setState calls
        val flowNameCount = output.split("Flow main.yml").size - 1
        assertThat(flowNameCount).isEqualTo(1)

        val runTestCount = output.split("Run test.yml").size - 1
        assertThat(runTestCount).isEqualTo(2)  // Once for start, once for complete
    }

    @Test
    fun `three levels of nested runFlow should all print`() {
        // Given
        val resultView = PlainTextResultView()

        // Level 3: deepest assert
        val assertCommand = MaestroCommand(
            assertConditionCommand = AssertConditionCommand(
                condition = Condition(visible = ElementSelector(textRegex = "deep"))
            )
        )
        val assertState = CommandState(
            status = CommandStatus.COMPLETED,
            command = assertCommand,
            subOnStartCommands = null,
            subOnCompleteCommands = null,
            subCommands = null
        )

        // Level 2: middle runFlow (level2.yml)
        val level2Flow = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(assertCommand),
                sourceDescription = "level2.yml",
                config = null
            )
        )
        val level2State = CommandState(
            status = CommandStatus.COMPLETED,
            command = level2Flow,
            subOnStartCommands = null,
            subOnCompleteCommands = null,
            subCommands = listOf(assertState)
        )

        // Level 1: outer runFlow (level1.yml)
        val level1Flow = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(level2Flow),
                sourceDescription = "level1.yml",
                config = null
            )
        )
        val level1State = CommandState(
            status = CommandStatus.COMPLETED,
            command = level1Flow,
            subOnStartCommands = null,
            subOnCompleteCommands = null,
            subCommands = listOf(level2State)
        )

        val state = UiState.Running(
            flowName = "main.yml",
            commands = listOf(level1State)
        )

        // When
        resultView.setState(state)

        // Then
        val output = getOutput()
        tearDown()

        // All levels should be printed
        assertThat(output).contains("Run level1.yml")
        assertThat(output).contains("Run level2.yml")
        assertThat(output).contains("Assert that")
    }
}

