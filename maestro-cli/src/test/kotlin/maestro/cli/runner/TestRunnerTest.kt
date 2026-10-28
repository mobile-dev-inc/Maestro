package maestro.cli.runner

import com.google.common.truth.Truth.assertThat
import maestro.Maestro
import maestro.cli.runner.resultview.ResultView
import maestro.cli.runner.resultview.UiState
import maestro.test.drivers.FakeDriver
import maestro.utils.TempFileHandler
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class TestRunnerTest {

    @Test
    fun `successful retry does not print the recovered assertion failure`() {
        val result = runFlow(retryFlow(successfulAttempt = 2))

        assertThat(result.exitCode).isEqualTo(0)
        assertThat(result.output).doesNotContain("Check the UI hierarchy in debug artifacts")
        assertThat(result.states.flatMap { it.commands }.flatMap(::flatten).map { it.insight.message })
            .contains("Retrying the commands due to an error: Assertion is false: false is true while execution (Attempt 1)")
    }

    @Test
    fun `exhausted retries still print the assertion failure`() {
        val result = runFlow(retryFlow(successfulAttempt = 3))

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.output).contains("Assertion is false")
        assertThat(result.output).contains("Check the UI hierarchy in debug artifacts")
    }

    @Test
    fun `completion hook failure still prints the assertion failure`() {
        val result = runFlow(
            """
            appId: com.example.app
            onFlowComplete:
              - assertTrue: ${'$'}{false}
            ---
            - assertTrue: ${'$'}{true}
            """.trimIndent()
        )

        assertThat(result.exitCode).isEqualTo(1)
        assertThat(result.output).contains("Assertion is false")
        assertThat(result.output).contains("Check the UI hierarchy in debug artifacts")
    }

    private fun retryFlow(successfulAttempt: Int) = """
        appId: com.example.app
        ---
        - evalScript: ${'$'}{output.attempt = 0}
        - retry:
            maxRetries: 1
            commands:
              - evalScript: ${'$'}{output.attempt++}
              - assertTrue: ${'$'}{output.attempt >= $successfulAttempt}
    """.trimIndent()

    private fun runFlow(yaml: String): FlowOutput = TempFileHandler().use { tempFiles ->
        val flow = tempFiles.createTempFile("flow", ".yaml").apply { writeText(yaml) }
        val debugDirectory = tempFiles.createTempDirectory("flow-debug")
        val driver = FakeDriver().apply { open() }
        Maestro(driver).use { maestro ->
            val output = ByteArrayOutputStream()
            val states = mutableListOf<UiState.Running>()
            val originalOut = System.out
            PrintStream(output).use { stream ->
                System.setOut(stream)
                try {
                    val exitCode = TestRunner.runSingle(
                        maestro = maestro,
                        device = null,
                        flowFile = flow,
                        env = emptyMap(),
                        resultView = object : ResultView {
                            override fun setState(state: UiState) {
                                if (state is UiState.Running) states.add(state)
                            }
                        },
                        debugOutputPath = debugDirectory.toPath(),
                        deviceId = null,
                    )
                    FlowOutput(exitCode, output.toString(Charsets.UTF_8), states)
                } finally {
                    System.setOut(originalOut)
                }
            }
        }
    }

    private data class FlowOutput(
        val exitCode: Int,
        val output: String,
        val states: List<UiState.Running>,
    )

    private fun flatten(command: maestro.cli.runner.CommandState): List<maestro.cli.runner.CommandState> =
        listOf(command) + (command.subCommands ?: emptyList()).flatMap(::flatten)
}
