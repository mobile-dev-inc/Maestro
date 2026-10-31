package maestro.cli.runner.resultview

import com.google.common.truth.Truth.assertThat
import maestro.cli.runner.CommandState
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.MaestroCommand
import maestro.orchestra.debug.CommandStatus
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Base64

class AnsiResultViewTest {

    private lateinit var originalOut: PrintStream

    @BeforeEach
    fun setUp() {
        originalOut = System.out
        System.setOut(PrintStream(ByteArrayOutputStream()))
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
    }

    private fun assertState(status: CommandStatus) = UiState.Running(
        flowName = "main.yml",
        commands = listOf(
            CommandState(
                status = status,
                command = MaestroCommand(
                    assertConditionCommand = AssertConditionCommand(
                        condition = Condition(visible = ElementSelector(textRegex = "hello"))
                    )
                ),
                subOnStartCommands = null,
                subOnCompleteCommands = null,
                subCommands = null
            )
        )
    )

    @Test
    fun `records each distinct screen it renders`() {
        // Given
        val frameRecorder = FrameRecorder()
        val resultView = AnsiResultView(frameRecorder = frameRecorder)

        // When
        resultView.setState(assertState(CommandStatus.RUNNING))
        resultView.setState(assertState(CommandStatus.RUNNING))
        resultView.setState(assertState(CommandStatus.COMPLETED))

        // Then
        val screens = frameRecorder.getFrames().map { String(Base64.getDecoder().decode(it.content), Charsets.UTF_8) }
        assertThat(screens).hasSize(2)
        assertThat(screens.last()).contains("Assert that")
    }
}
