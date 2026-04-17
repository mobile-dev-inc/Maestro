package maestro.orchestra.debug

import com.google.common.truth.Truth.assertThat
import maestro.orchestra.MaestroCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class TestOutputWriterTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `saveCommands writes commands JSON when commands map is non-empty`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val cmd = MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(
                status = CommandStatus.COMPLETED,
                timestamp = 123L,
                duration = 10L,
                sequenceNumber = 0,
            )
        }

        TestOutputWriter.saveCommands(outputDir, debug, commandsFilename = "commands-(my_flow).json")

        val file = outputDir.resolve("commands-(my_flow).json").toFile()
        assertThat(file.exists()).isTrue()
        assertThat(file.readText()).contains("\"status\" : \"COMPLETED\"")
    }

    @Test
    fun `saveCommands writes no JSON when commands map is empty`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val debug = FlowDebugOutput()

        TestOutputWriter.saveCommands(outputDir, debug, commandsFilename = "commands.json")

        val listed = outputDir.toFile().listFiles()?.toList().orEmpty()
        assertThat(listed.any { it.name.startsWith("commands") }).isFalse()
    }

    @Test
    fun `saveCommands honors caller-supplied filename exactly`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val cmd = MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(status = CommandStatus.COMPLETED)
        }

        TestOutputWriter.saveCommands(outputDir, debug, commandsFilename = "commands-shard-1-(my_flow).json")

        assertThat(outputDir.resolve("commands-shard-1-(my_flow).json").toFile().exists()).isTrue()
    }

    @Test
    fun `saveScreenshots copies each named screenshot into the target directory`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot1 = Files.createFile(tempDir.resolve("raw1.png")).toFile()
        shot1.writeBytes(byteArrayOf(1, 2, 3))
        val shot2 = Files.createFile(tempDir.resolve("raw2.png")).toFile()
        shot2.writeBytes(byteArrayOf(4, 5))

        TestOutputWriter.saveScreenshots(
            outputDir,
            listOf(
                TestOutputWriter.NamedScreenshot(shot1, "screenshot-✅-1-(my_flow).png"),
                TestOutputWriter.NamedScreenshot(shot2, "screenshot-❌-2-(my_flow).png"),
            ),
        )

        val a = outputDir.resolve("screenshot-✅-1-(my_flow).png").toFile()
        val b = outputDir.resolve("screenshot-❌-2-(my_flow).png").toFile()
        assertThat(a.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(b.readBytes()).isEqualTo(byteArrayOf(4, 5))
    }

    @Test
    fun `saveScreenshots writes no files when list is empty`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))

        TestOutputWriter.saveScreenshots(outputDir, emptyList())

        val listed = outputDir.toFile().listFiles()?.toList().orEmpty()
        assertThat(listed).isEmpty()
    }

    @Test
    fun `emojiFor maps COMPLETED to check mark`() {
        assertThat(TestOutputWriter.emojiFor(CommandStatus.COMPLETED)).isEqualTo("✅")
    }

    @Test
    fun `emojiFor maps FAILED to cross mark`() {
        assertThat(TestOutputWriter.emojiFor(CommandStatus.FAILED)).isEqualTo("❌")
    }

    @Test
    fun `emojiFor maps WARNED to warning sign`() {
        assertThat(TestOutputWriter.emojiFor(CommandStatus.WARNED)).isEqualTo("⚠\uFE0F")
    }

    @Test
    fun `emojiFor maps other statuses to question mark`() {
        assertThat(TestOutputWriter.emojiFor(CommandStatus.SKIPPED)).isEqualTo("﹖")
        assertThat(TestOutputWriter.emojiFor(CommandStatus.PENDING)).isEqualTo("﹖")
        assertThat(TestOutputWriter.emojiFor(CommandStatus.RUNNING)).isEqualTo("﹖")
    }
}
