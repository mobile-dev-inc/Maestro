package maestro.cli.mcp.tools

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.ArtifactKind
import maestro.orchestra.BackPressCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.RunFlowCommand
import maestro.orchestra.debug.CommandArtifact
import maestro.orchestra.debug.CommandDebugMetadata
import maestro.orchestra.debug.CommandStatus
import maestro.orchestra.debug.FlowDebugOutput
import maestro.orchestra.debug.TestOutputWriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FlowRunnerTest {

    @TempDir
    lateinit var flowDir: Path

    @Test
    fun `failedStepScreenshot prefers the failed leaf over its failed parent`() {
        val leaf = MaestroCommand(BackPressCommand())
        val parent = MaestroCommand(RunFlowCommand(commands = listOf(leaf), config = null))
        writeCommands(
            step(parent, CommandStatus.FAILED, sequence = 0, CommandArtifact(ArtifactKind.SCREENSHOT, "screenshots/step-000.png")),
            step(
                leaf, CommandStatus.FAILED, sequence = 1,
                CommandArtifact(ArtifactKind.SCREENSHOT, "screenshots/step-001.png"),
                CommandArtifact(ArtifactKind.SCREEN_HIERARCHY, "screen-hierarchy/step-001.json"),
            ),
        )
        touch("screenshots/step-000.png")
        touch("screenshots/step-001.png")

        val screenshot = FlowRunner.failedStepScreenshot(flowDir)

        assertThat(screenshot).isEqualTo(flowDir.resolve("screenshots/step-001.png").toFile())
    }

    @Test
    fun `failedStepScreenshot ignores screenshots of steps that did not fail`() {
        writeCommands(
            step(
                MaestroCommand(BackPressCommand()), CommandStatus.WARNED, sequence = 0,
                CommandArtifact(ArtifactKind.SCREENSHOT, "screenshots/step-000.png"),
            ),
        )
        touch("screenshots/step-000.png")

        assertThat(FlowRunner.failedStepScreenshot(flowDir)).isNull()
    }

    @Test
    fun `failedStepScreenshot is null without a commands file`() {
        assertThat(FlowRunner.failedStepScreenshot(flowDir)).isNull()
    }

    private fun step(
        command: MaestroCommand,
        status: CommandStatus,
        sequence: Int,
        vararg artifacts: CommandArtifact,
    ) = CommandDebugMetadata(status = status, sequenceNumber = sequence, command = command).apply {
        this.artifacts.addAll(artifacts)
    }

    // Written by the real bundle writer, so a change to commands.json's shape fails here.
    private fun writeCommands(vararg steps: CommandDebugMetadata) {
        val debugOutput = FlowDebugOutput()
        debugOutput.executedSteps.addAll(steps)
        TestOutputWriter.saveCommands(flowDir, debugOutput, "commands.json")
    }

    private fun touch(relativePath: String) {
        flowDir.resolve(relativePath).toFile().apply {
            parentFile.mkdirs()
            writeBytes(byteArrayOf(0))
        }
    }
}
