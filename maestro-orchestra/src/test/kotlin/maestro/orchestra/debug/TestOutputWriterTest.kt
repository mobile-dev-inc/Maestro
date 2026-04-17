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
    fun `saveFlow writes commands JSON when commands map is non-empty`() {
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

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val file = outputDir.resolve("commands-(my_flow).json").toFile()
        assertThat(file.exists()).isTrue()
        assertThat(file.readText()).contains("\"status\" : \"COMPLETED\"")
    }

    @Test
    fun `saveFlow writes no commands JSON when commands map is empty`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val debug = FlowDebugOutput()

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val listed = outputDir.toFile().listFiles()?.toList().orEmpty()
        assertThat(listed.any { it.name.startsWith("commands-") }).isFalse()
    }

    @Test
    fun `saveFlow tags screenshots with COMPLETED emoji`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        shot.writeBytes(byteArrayOf(1, 2, 3))
        val debug = FlowDebugOutput().apply {
            screenshots.add(FlowDebugOutput.Screenshot(shot, 999L, CommandStatus.COMPLETED))
        }

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val written = outputDir.toFile().listFiles()!!
            .first { it.name.startsWith("screenshot-") }
        assertThat(written.name).isEqualTo("screenshot-✅-999-(my_flow).png")
        assertThat(written.readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `saveFlow tags screenshots with FAILED emoji`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val debug = FlowDebugOutput().apply {
            screenshots.add(FlowDebugOutput.Screenshot(shot, 111L, CommandStatus.FAILED))
        }

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val written = outputDir.toFile().listFiles()!!
            .first { it.name.startsWith("screenshot-") }
        assertThat(written.name).isEqualTo("screenshot-❌-111-(my_flow).png")
    }

    @Test
    fun `saveFlow tags screenshots with WARNED emoji`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val debug = FlowDebugOutput().apply {
            screenshots.add(FlowDebugOutput.Screenshot(shot, 222L, CommandStatus.WARNED))
        }

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val written = outputDir.toFile().listFiles()!!
            .first { it.name.startsWith("screenshot-") }
        assertThat(written.name).isEqualTo("screenshot-⚠\uFE0F-222-(my_flow).png")
    }

    @Test
    fun `saveFlow tags screenshots with unknown emoji for other statuses`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val debug = FlowDebugOutput().apply {
            screenshots.add(FlowDebugOutput.Screenshot(shot, 333L, CommandStatus.SKIPPED))
        }

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val written = outputDir.toFile().listFiles()!!
            .first { it.name.startsWith("screenshot-") }
        assertThat(written.name).isEqualTo("screenshot-﹖-333-(my_flow).png")
    }

    @Test
    fun `saveFlow writes no screenshot files when screenshots list is empty`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val debug = FlowDebugOutput()

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val listed = outputDir.toFile().listFiles()?.toList().orEmpty()
        assertThat(listed.any { it.name.startsWith("screenshot-") }).isFalse()
    }

    @Test
    fun `saveFlow with filenamePrefix prefixes command and screenshot filenames`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val cmd = MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(status = CommandStatus.COMPLETED, timestamp = 1L)
            screenshots.add(FlowDebugOutput.Screenshot(shot, 555L, CommandStatus.COMPLETED))
        }

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug, filenamePrefix = "shard-1-")

        val names = outputDir.toFile().listFiles()!!.map { it.name }
        assertThat(names).contains("commands-shard-1-(my_flow).json")
        assertThat(names).contains("screenshot-shard-1-✅-555-(my_flow).png")
    }

    @Test
    fun `saveFlow with default filenamePrefix does not prefix filenames`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val cmd = MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(status = CommandStatus.COMPLETED, timestamp = 1L)
            screenshots.add(FlowDebugOutput.Screenshot(shot, 555L, CommandStatus.COMPLETED))
        }

        TestOutputWriter.saveFlow(outputDir, "my_flow", debug)

        val names = outputDir.toFile().listFiles()!!.map { it.name }
        assertThat(names).contains("commands-(my_flow).json")
        assertThat(names).contains("screenshot-✅-555-(my_flow).png")
    }

    @Test
    fun `saveFlow replaces slashes in flow name with underscores in commands filename`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val cmd = MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(status = CommandStatus.COMPLETED)
        }

        TestOutputWriter.saveFlow(outputDir, "feature/login", debug)

        assertThat(outputDir.resolve("commands-(feature_login).json").toFile().exists()).isTrue()
    }
}
