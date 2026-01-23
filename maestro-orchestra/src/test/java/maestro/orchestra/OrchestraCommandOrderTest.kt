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
        val sequence: Int,
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
            val startEvent = cmdEvents.find { it.type == "onCommandStart" }
            val metadataEvent = cmdEvents.find { it.type == "onCommandMetadataUpdate" }
            val completeEvent = cmdEvents.find { it.type == "onCommandComplete" }
            
            // Verify onCommandStart comes before onCommandMetadataUpdate
            if (startEvent != null && metadataEvent != null) {
                assertThat(startEvent.sequence).isLessThan(metadataEvent.sequence)
            }
            // Verify onCommandMetadataUpdate comes before onCommandComplete
            if (metadataEvent != null && completeEvent != null) {
                assertThat(metadataEvent.sequence).isLessThan(completeEvent.sequence)
            }
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
        
        // Track current command index for metadata updates (which don't receive index)
        var currentCommandIndex = 0

        return Orchestra(
            maestro = maestro,
            lookupTimeoutMs = 0L,
            optionalLookupTimeoutMs = 0L,
            onCommandStart = { index, _ ->
                currentCommandIndex = index
                events.add(CallbackEvent("onCommandStart", index, getSequence()))
            },
            onCommandMetadataUpdate = { _, _ ->
                events.add(CallbackEvent("onCommandMetadataUpdate", currentCommandIndex, getSequence()))
            },
            onCommandComplete = { index, _ ->
                events.add(CallbackEvent("onCommandComplete", index, getSequence()))
            },
            onCommandFailed = { index, _, _ ->
                events.add(CallbackEvent("onCommandFailed", index, getSequence()))
                Orchestra.ErrorResolution.FAIL
            },
            onCommandWarned = { index, _ ->
                events.add(CallbackEvent("onCommandWarned", index, getSequence()))
            },
            onCommandSkipped = { index, _ ->
                events.add(CallbackEvent("onCommandSkipped", index, getSequence()))
            },
        )
    }
}
