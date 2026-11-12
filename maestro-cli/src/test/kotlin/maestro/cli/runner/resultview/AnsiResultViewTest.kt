package maestro.cli.runner.resultview

import com.google.common.truth.Truth.assertThat
import maestro.cli.runner.CommandState
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.MaestroCommand
import maestro.orchestra.debug.CommandStatus
import org.jline.jansi.Ansi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.Base64

class AnsiResultViewTest {

    private lateinit var originalOut: PrintStream
    private lateinit var output: ByteArrayOutputStream
    private var ansiWasEnabled = true

    @BeforeEach
    fun setUp() {
        originalOut = System.out
        output = ByteArrayOutputStream()
        System.setOut(PrintStream(output))
        ansiWasEnabled = Ansi.isEnabled()
    }

    @AfterEach
    fun tearDown() {
        System.setOut(originalOut)
        Ansi.setEnabled(ansiWasEnabled)
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

    private fun FrameRecorder.screens() = getFrames().map { String(Base64.getDecoder().decode(it.content), Charsets.UTF_8) }

    @Test
    fun `records each screen it renders`() {
        // Given
        val frameRecorder = FrameRecorder()
        val resultView = AnsiResultView(frameRecorder = frameRecorder)

        // When
        resultView.setState(assertState(CommandStatus.RUNNING))
        resultView.setState(assertState(CommandStatus.RUNNING))
        resultView.setState(assertState(CommandStatus.COMPLETED))

        // Then
        val screens = frameRecorder.screens()
        assertThat(screens).hasSize(3)
        assertThat(screens.last()).contains("Assert that")
    }

    @Test
    fun `records without printing when not printing to the terminal`() {
        // Given
        val frameRecorder = FrameRecorder()
        val resultView = AnsiResultView(frameRecorder = frameRecorder, printToTerminal = false)

        // When
        resultView.setState(assertState(CommandStatus.RUNNING))
        resultView.setState(assertState(CommandStatus.COMPLETED))

        // Then
        assertThat(output.toString(Charsets.UTF_8)).isEmpty()
        assertThat(frameRecorder.screens()).hasSize(2)
    }

    @Test
    fun `records the status board when ANSI is disabled`() {
        // Given
        Ansi.setEnabled(false)
        val frameRecorder = FrameRecorder()
        val resultView = AnsiResultView(frameRecorder = frameRecorder, printToTerminal = false)

        // When
        resultView.setState(assertState(CommandStatus.COMPLETED))

        // Then
        val screen = frameRecorder.screens().single()
        assertThat(screen).contains("✅")
        assertThat(screen).contains("Assert that")
    }
}
