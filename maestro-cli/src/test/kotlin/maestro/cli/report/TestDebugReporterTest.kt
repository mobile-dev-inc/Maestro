package maestro.cli.report

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import maestro.cli.util.EnvUtils
import maestro.orchestra.debug.CommandDebugMetadata
import maestro.orchestra.debug.CommandStatus
import maestro.orchestra.debug.FlowDebugOutput
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.pathString


class TestDebugReporterTest {

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        // Reset singleton state between tests.
        // updateTestOutputDir clears testOutputDir and debugOutputPath;
        // install() called in each test resets the remaining fields.
        TestDebugReporter.updateTestOutputDir(null)
    }

    @Test
    fun `will delete old files`() {
        // Create directory structure, and an old test directory
        val oldDir = Files.createDirectories(tempDir.resolve(".maestro/tests/old"))
        Files.setLastModifiedTime(oldDir, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 1000 * 60 * 60 * 24 * 15))

        // Initialise a new test reporter, which will create ./maestro/tests/<datestamp>
        TestDebugReporter.install(debugOutputPathAsString = tempDir.pathString, flattenDebugOutput = false, printToConsole = false)

        // Run the deleteOldFiles method, which happens at the end of each test run
        // This should delete the 'old' directory created above
        TestDebugReporter.deleteOldFiles()
        assertThat(Files.exists(oldDir)).isFalse() // Verify that the old directory was deleted
        assertThat(TestDebugReporter.getDebugOutputPath().exists()).isTrue() // Verify that the logs from this run still exist
    }

    @Test
    fun `getDebugOutputPath with custom debug output places path under custom root`() {
        TestDebugReporter.install(debugOutputPathAsString = tempDir.pathString, flattenDebugOutput = false, printToConsole = false)

        val path = TestDebugReporter.getDebugOutputPath()

        assertThat(path.startsWith(tempDir.resolve(".maestro/tests"))).isTrue()
    }

    @Test
    fun `getDebugOutputPath with flattenDebugOutput returns the root directly`() {
        TestDebugReporter.install(tempDir.pathString, flattenDebugOutput = true, printToConsole = false)

        assertThat(TestDebugReporter.getDebugOutputPath()).isEqualTo(tempDir)
    }

    @Test
    fun `getDebugOutputPath with flattenDebugOutput ignores testOutputDir`() {
        TestDebugReporter.install(debugOutputPathAsString = tempDir.pathString, flattenDebugOutput = true, printToConsole = false)

        val testOutputDir = Files.createDirectories(tempDir.resolve("test-output"))
        TestDebugReporter.updateTestOutputDir(testOutputDir)
        val path = TestDebugReporter.getDebugOutputPath()

        assertThat(path).isEqualTo(tempDir)
    }

    @Test
    fun `getDebugOutputPath returns same path after updateTestOutputDir with null`() {
        TestDebugReporter.install(debugOutputPathAsString = tempDir.pathString, flattenDebugOutput = false, printToConsole = false)
        val firstPath = TestDebugReporter.getDebugOutputPath()

        TestDebugReporter.updateTestOutputDir(null)
        val secondPath = TestDebugReporter.getDebugOutputPath()

        assertThat(secondPath).isEqualTo(firstPath)
    }

    @Test
    fun `getDebugOutputPath uses same folder name after updateTestOutputDir`() {
        TestDebugReporter.install(debugOutputPathAsString = tempDir.pathString, flattenDebugOutput = false, printToConsole = false)
        val folderName = TestDebugReporter.getDebugOutputPath().fileName.toString()

        val testOutputDir = Files.createDirectories(tempDir.resolve("test-output"))
        TestDebugReporter.updateTestOutputDir(testOutputDir)
        val newPath = TestDebugReporter.getDebugOutputPath()

        assertThat(newPath.fileName.toString()).isEqualTo(folderName)
        assertThat(newPath.startsWith(testOutputDir)).isTrue()
    }

    @Test
    fun `getDebugOutputPath uses testOutputDir as base when set`() {
        val testOutputDir = Files.createDirectories(tempDir.resolve("test-output"))
        TestDebugReporter.install(debugOutputPathAsString = tempDir.pathString, flattenDebugOutput = false, printToConsole = false)
        TestDebugReporter.updateTestOutputDir(testOutputDir)

        val path = TestDebugReporter.getDebugOutputPath()

        assertThat(path.startsWith(testOutputDir)).isTrue()
    }

    @Test
    fun `getDebugOutputPath defaults to xdgStateHome when no custom path provided`() {
        val xdgMaestroDir = tempDir.resolve("xdg/maestro")
        mockkObject(EnvUtils)
        try {
            every { EnvUtils.xdgStateHome() } returns xdgMaestroDir

            TestDebugReporter.install(debugOutputPathAsString = null, flattenDebugOutput = false, printToConsole = false)
            val path = TestDebugReporter.getDebugOutputPath()

            assertThat(path.startsWith(xdgMaestroDir.resolve("tests"))).isTrue()
        } finally {
            unmockkObject(EnvUtils)
        }
    }

    @Test
    fun `saveSuggestions should not write ai files when flow has no ai outputs`() {
        val outputDir = Files.createDirectories(tempDir.resolve("debug-output"))

        val outputs = listOf(
            FlowAIOutput(
                flowName = "login_test",
                flowFile = File("flows/login_test.yaml"),
            ),
            FlowAIOutput(
                flowName = "signup_test",
                flowFile = File("flows/signup_test.yaml"),
            ),
        )

        TestDebugReporter.saveSuggestions(outputs, outputDir)

        val files = outputDir.toFile().listFiles()?.map { it.name } ?: emptyList()
        assertThat(files.filter { it.startsWith("ai-") }).isEmpty()
    }

    @Test
    fun `saveFlow with shardIndex 2 produces shard-3 prefixed filenames via facade`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val shot = Files.createFile(tempDir.resolve("raw.png")).toFile()
        val cmd = maestro.orchestra.MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(status = CommandStatus.COMPLETED, timestamp = 1L)
            screenshots.add(FlowDebugOutput.Screenshot(shot, 555L, CommandStatus.COMPLETED))
        }

        TestDebugReporter.saveFlow("my_flow", debug, outputDir, shardIndex = 2)

        val names = outputDir.toFile().listFiles()!!.map { it.name }
        assertThat(names).contains("commands-shard-3-(my_flow).json")
        assertThat(names).contains("screenshot-shard-3-✅-555-(my_flow).png")
    }

    @Test
    fun `saveFlow replaces slashes in flow name with underscores in commands filename via facade`() {
        val outputDir = Files.createDirectories(tempDir.resolve("out"))
        val cmd = maestro.orchestra.MaestroCommand(tapOnElement = null)
        val debug = FlowDebugOutput().apply {
            commands[cmd] = CommandDebugMetadata(status = CommandStatus.COMPLETED)
        }

        TestDebugReporter.saveFlow("feature/login", debug, outputDir)

        assertThat(outputDir.resolve("commands-(feature_login).json").toFile().exists()).isTrue()
    }

}
