package maestro.orchestra.debug

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import maestro.Maestro
import maestro.MaestroException
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.orchestra.MaestroCommand
import okio.Buffer
import okio.Sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class ArtifactsGeneratorTest {

    @TempDir
    lateinit var tempDir: Path

    private fun mockMaestro(
        screenshotBytes: ByteArray = byteArrayOf(1, 2, 3, 4),
        hierarchyRoot: TreeNode = TreeNode(attributes = mutableMapOf("text" to "root")),
    ): Maestro = mockk(relaxed = true) {
        coEvery { takeScreenshot(any<Sink>(), any()) } answers {
            val sink = firstArg<Sink>()
            val buffer = Buffer().write(screenshotBytes)
            sink.write(buffer, buffer.size)
            sink.flush()
        }
        coEvery { viewHierarchy(any()) } returns ViewHierarchy(hierarchyRoot)
    }

    @Test
    fun `populates debugOutput in memory even when artifactsDir is null`() {
        val gen = ArtifactsGenerator(artifactsDir = null, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, startedAt = 100L, finishedAt = 150L)
        gen.onFlowEnd()

        val metadata = gen.debugOutput.commands[cmd]!!
        assertThat(metadata.status).isEqualTo(CommandStatus.COMPLETED)
        assertThat(metadata.duration).isEqualTo(50L)
        assertThat(metadata.sequenceNumber).isEqualTo(0)
    }

    @Test
    fun `writes commands_json to artifactsDir at onFlowEnd`() {
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, sequenceNumber = 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, startedAt = 100L, finishedAt = 150L)
        gen.onFlowEnd()

        assertThat(tempDir.resolve("commands.json").exists()).isTrue()
        val content = Files.readString(tempDir.resolve("commands.json"))
        assertThat(content).contains("\"status\" : \"COMPLETED\"")
    }

    @Test
    fun `does not write commands_json when artifactsDir is null`() {
        val gen = ArtifactsGenerator(artifactsDir = null, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 150L)
        gen.onFlowEnd()

        // tempDir untouched: ArtifactsGenerator wasn't given it.
        assertThat(tempDir.toFile().listFiles()?.isEmpty()).isTrue()
    }

    @Test
    fun `on failure with artifactsDir, captures hierarchy and screenshot independently`() {
        val maestro = mockMaestro()
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = maestro)
        val cmd = MaestroCommand(tapOnElement = null)
        val error = RuntimeException("boom")

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(error), 100L, 200L)
        gen.onFlowEnd()

        // Hierarchy attached inline on the metadata.
        val metadata = gen.debugOutput.commands[cmd]!!
        assertThat(metadata.status).isEqualTo(CommandStatus.FAILED)
        assertThat(metadata.error).isEqualTo(error)
        assertThat(metadata.hierarchy).isNotNull()

        // Failure screenshot written as a separate file in the artifacts dir.
        val screenshots = tempDir.toFile().listFiles { _, n -> n.startsWith("screenshot-❌-") }
        assertThat(screenshots).isNotNull()
        assertThat(screenshots!!.size).isEqualTo(1)
        assertThat(gen.debugOutput.screenshots).hasSize(1)
        assertThat(gen.debugOutput.screenshots[0].status).isEqualTo(CommandStatus.FAILED)
    }

    @Test
    fun `screenshot failure does not block hierarchy capture`() {
        // Screenshot throws; hierarchy still lands on the metadata.
        val maestro: Maestro = mockk(relaxed = true) {
            coEvery { takeScreenshot(any<Sink>(), any()) } throws RuntimeException("screenshot boom")
            coEvery { viewHierarchy(any()) } returns ViewHierarchy(
                TreeNode(attributes = mutableMapOf("text" to "root"))
            )
        }
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = maestro)
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(RuntimeException("test")), 100L, 200L)
        gen.onFlowEnd()

        val metadata = gen.debugOutput.commands[cmd]!!
        assertThat(metadata.hierarchy).isNotNull()
        // No screenshot file landed (capture threw)
        val screenshots = tempDir.toFile().listFiles { _, n -> n.startsWith("screenshot-❌-") } ?: emptyArray()
        assertThat(screenshots).isEmpty()
    }

    @Test
    fun `hierarchy failure does not block screenshot capture`() {
        // Hierarchy throws; screenshot still lands.
        val maestro: Maestro = mockk(relaxed = true) {
            coEvery { takeScreenshot(any<Sink>(), any()) } answers {
                val sink = firstArg<Sink>()
                val buf = Buffer().write(byteArrayOf(1, 2, 3))
                sink.write(buf, buf.size)
                sink.flush()
            }
            coEvery { viewHierarchy(any()) } throws RuntimeException("hierarchy boom")
        }
        val gen = ArtifactsGenerator(artifactsDir = tempDir, maestro = maestro)
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onFlowStart()
        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(RuntimeException("test")), 100L, 200L)
        gen.onFlowEnd()

        val metadata = gen.debugOutput.commands[cmd]!!
        assertThat(metadata.hierarchy).isNull()
        val screenshots = tempDir.toFile().listFiles { _, n -> n.startsWith("screenshot-❌-") }
        assertThat(screenshots).isNotNull()
        assertThat(screenshots!!.size).isEqualTo(1)
    }

    @Test
    fun `MaestroException on failure populates debugOutput_exception`() {
        val gen = ArtifactsGenerator(artifactsDir = null, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)
        val mErr = MaestroException.UnableToLaunchApp("nope")

        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Failed(mErr), 100L, 200L)

        assertThat(gen.debugOutput.exception).isEqualTo(mErr)
    }

    @Test
    fun `onCommandReset transitions status to PENDING`() {
        val gen = ArtifactsGenerator(artifactsDir = null, maestro = mockMaestro())
        val cmd = MaestroCommand(tapOnElement = null)

        gen.onCommandStart(cmd, 0)
        gen.onCommandFinished(cmd, CommandOutcome.Completed, 100L, 200L)
        gen.onCommandReset(cmd)

        assertThat(gen.debugOutput.commands[cmd]!!.status).isEqualTo(CommandStatus.PENDING)
    }
}
