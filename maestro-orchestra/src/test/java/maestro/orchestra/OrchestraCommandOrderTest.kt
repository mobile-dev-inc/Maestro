package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.test.drivers.FakeDriver
import maestro.test.drivers.FakeLayoutElement
import org.junit.jupiter.api.Test

internal class OrchestraCommandOrderTest {

    private data class CallbackEvent(
        val type: String,
        val commandIndex: Int,
        val sequence: Int
    )

    @Test
    fun `callback order should be correct for successful command in subflow`() {
        // Given
        val events = mutableListOf<CallbackEvent>()
        var sequence = 0
        val subflowCommand = MaestroCommand(BackPressCommand())
        val runFlowCommand = RunFlowCommand(
            commands = listOf(subflowCommand),
            condition = null,
            sourceDescription = null,
            config = null,
            label = null,
            optional = false,
        )
        val commands = listOf(MaestroCommand(runFlowCommand))

        val orchestra = createOrchestraWithCallbacks(events) { sequence++ }

        // When
        runBlocking {
            orchestra.runFlow(commands)
        }

        // Then
        // Expected order: onCommandStart -> onCommandMetadataUpdate -> onCommandComplete
        // For subflow, verify the critical ordering is maintained for each command
        // (subflow execution includes both RunFlowCommand and subflow command events)
        val commandIndexes = events.map { it.commandIndex }.distinct()
        for (cmdIndex in commandIndexes) {
            val cmdEvents = events.filter { it.commandIndex == cmdIndex }
            assertThat(cmdEvents.map { it.type }).containsExactly(
                "onCommandStart",
                "onCommandMetadataUpdate",
                "onCommandComplete"
            ).inOrder()
        }
    }

    @Test
    fun `callback order should be correct for successful command in main flow`() {
        // Given
        val events = mutableListOf<CallbackEvent>()
        var sequence = 0
        val command = MaestroCommand(BackPressCommand())
        val commands = listOf(command)

        val orchestra = createOrchestraWithCallbacks(events) { sequence++ }

        // When
        runBlocking {
          orchestra.runFlow(commands)
        }

        // Then
        // Expected order: onCommandStart -> onCommandMetadataUpdate -> onCommandComplete
        val commandEvents = events.filter { it.commandIndex == 0 }
        assertThat(commandEvents.map { it.type }).containsExactly(
            "onCommandStart",
            "onCommandMetadataUpdate",
            "onCommandComplete"
        ).inOrder()
    }

    @Test
    fun `callback order should be correct for failed command in main flow`() {
        // Given
        val events = mutableListOf<CallbackEvent>()
        var sequence = 0
        // Use an assertion that will fail (element doesn't exist)
        val command = MaestroCommand(
            AssertConditionCommand(
                condition = Condition(
                    visible = ElementSelector(
                        idRegex = "non_existent_element"
                    )
                )
            )
        )
        val commands = listOf(command)

        val orchestra = createOrchestraWithCallbacks(events) { sequence++ }

        // When
        runBlocking {
            try {
                orchestra.runFlow(commands)
            } catch (e: Throwable) {
                // Expected to fail, ignore the exception
            }
        }

        // Then
        // Expected order: onCommandStart -> onCommandMetadataUpdate -> onCommandFailed
        val commandEvents = events.filter { it.commandIndex == 0 }
        assertThat(commandEvents.map { it.type }).containsExactly(
            "onCommandStart",
            "onCommandMetadataUpdate",
            "onCommandFailed"
        ).inOrder()
    }

    private fun createOrchestraWithCallbacks(
        events: MutableList<CallbackEvent>,
        getSequence: () -> Int,
    ): Orchestra {
        val driver = FakeDriver()
        driver.setLayout(FakeLayoutElement())
        driver.open()
        val maestro = Maestro(driver)

        // Track unique command index that increments for each command start
        // This ensures subflow commands get different indices than parent flow commands
        var uniqueCommandIndex = -1
        // Use a stack to track active commands (handles nested commands that reuse Orchestra indices)
        val activeCommandStack = mutableListOf<Int>()

        return Orchestra(
            maestro = maestro,
            lookupTimeoutMs = 0L,
            optionalLookupTimeoutMs = 0L,
            onCommandStart = { _, _ ->
                uniqueCommandIndex++
                activeCommandStack.add(uniqueCommandIndex)
                events.add(CallbackEvent("onCommandStart", uniqueCommandIndex, getSequence()))
            },
            onCommandMetadataUpdate = { _, _ ->
                // Use the most recent active command (top of stack)
                val uniqueIndex = activeCommandStack.lastOrNull() ?: 0
                events.add(CallbackEvent("onCommandMetadataUpdate", uniqueIndex, getSequence()))
            },
            onCommandComplete = { _, _ ->
                // Pop the most recent command from the stack (LIFO for nested commands)
                val uniqueIndex = activeCommandStack.removeLastOrNull() ?: 0
                events.add(CallbackEvent("onCommandComplete", uniqueIndex, getSequence()))
            },
            onCommandFailed = { _, _, _ ->
                // Pop the most recent command from the stack (LIFO for nested commands)
                val uniqueIndex = activeCommandStack.removeLastOrNull() ?: 0
                events.add(CallbackEvent("onCommandFailed", uniqueIndex, getSequence()))
                Orchestra.ErrorResolution.FAIL
            },
            onCommandWarned = { _, _ ->
                // Use the most recent active command (top of stack)
                val uniqueIndex = activeCommandStack.lastOrNull() ?: 0
                events.add(CallbackEvent("onCommandWarned", uniqueIndex, getSequence()))
            },
            onCommandSkipped = { _, _ ->
                // Pop the most recent command from the stack (LIFO for nested commands)
                val uniqueIndex = activeCommandStack.removeLastOrNull() ?: 0
                events.add(CallbackEvent("onCommandSkipped", uniqueIndex, getSequence()))
            },
        )
    }
}
