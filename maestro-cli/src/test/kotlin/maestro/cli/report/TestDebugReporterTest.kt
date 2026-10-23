package maestro.cli.report

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import maestro.cli.util.EnvUtils
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
    fun `createFlowDir creates a per-flow folder with a clean name`() {
        val destDir = Files.createDirectories(tempDir.resolve("session"))

        val flowDir = TestDebugReporter.createFlowDir(destDir, flowName = "login")

        assertThat(flowDir).isEqualTo(destDir.resolve("login"))
        assertThat(flowDir.toFile().isDirectory).isTrue()
    }

    @Test
    fun `createFlowDir sanitizes slashes and applies shard suffix`() {
        val destDir = Files.createDirectories(tempDir.resolve("session"))

        val flowDir = TestDebugReporter.createFlowDir(destDir, flowName = "feature/login", shardIndex = 2)

        assertThat(flowDir).isEqualTo(destDir.resolve("feature_login-shard-3"))
        assertThat(flowDir.toFile().isDirectory).isTrue()
    }

    @Test
    fun `createFlowDir disambiguates same-name flows with a numeric suffix`() {
        val destDir = Files.createDirectories(tempDir.resolve("session"))

        val first = TestDebugReporter.createFlowDir(destDir, flowName = "dup")
        val second = TestDebugReporter.createFlowDir(destDir, flowName = "dup")

        assertThat(first).isEqualTo(destDir.resolve("dup"))
        assertThat(second).isEqualTo(destDir.resolve("dup-2"))
    }

    @Test
    fun `createFlowDir shortens a flow name over the file name limit`() {
        val destDir = Files.createDirectories(tempDir.resolve("session"))

        val flowDir = TestDebugReporter.createFlowDir(destDir, flowName = "A".repeat(300))

        val name = flowDir.fileName.toString()
        assertThat(flowDir.toFile().isDirectory).isTrue()
        assertThat(name.toByteArray(Charsets.UTF_8).size).isAtMost(255)
        assertThat(name).startsWith("AAAA")
    }

    @Test
    fun `createFlowDir keeps long flow names that share a prefix apart`() {
        val destDir = Files.createDirectories(tempDir.resolve("session"))
        val prefix = "A".repeat(300)

        val first = TestDebugReporter.createFlowDir(destDir, flowName = "${prefix}1")
        val second = TestDebugReporter.createFlowDir(destDir, flowName = "${prefix}2")

        assertThat(first).isNotEqualTo(second)
        assertThat(first.fileName.toString()).doesNotMatch(".*-2$")
        assertThat(second.fileName.toString()).doesNotMatch(".*-2$")
    }

    @Test
    fun `createFlowDir keeps the shard and numeric suffixes within the limit for long names`() {
        val destDir = Files.createDirectories(tempDir.resolve("session"))
        val longName = "A".repeat(300)

        val first = TestDebugReporter.createFlowDir(destDir, flowName = longName, shardIndex = 0)
        val second = TestDebugReporter.createFlowDir(destDir, flowName = longName, shardIndex = 0)

        assertThat(first.fileName.toString()).endsWith("-shard-1")
        assertThat(second.fileName.toString()).endsWith("-shard-1-2")
        assertThat(second.fileName.toString().toByteArray(Charsets.UTF_8).size).isAtMost(255)
    }

    @Test
    fun `createFlowDir does not split multi-byte characters when shortening`() {
        val destDir = Files.createDirectories(tempDir.resolve("session"))

        val flowDir = TestDebugReporter.createFlowDir(destDir, flowName = "\u00e9".repeat(200))

        val name = flowDir.fileName.toString()
        assertThat(flowDir.toFile().isDirectory).isTrue()
        assertThat(name.toByteArray(Charsets.UTF_8).size).isAtMost(255)
        assertThat(name.substringBeforeLast("-").all { it == '\u00e9' }).isTrue()
    }

    @Test
    fun `createFlowDir steps back to a character boundary when the limit falls mid-character`() {
        val destDir = Files.createDirectories(tempDir.resolve("session"))

        // The leading "a" puts every 2-byte "\u00e9" at an odd offset, so the byte limit lands inside one
        val flowDir = TestDebugReporter.createFlowDir(destDir, flowName = "a" + "\u00e9".repeat(200))

        val name = flowDir.fileName.toString()
        assertThat(flowDir.toFile().isDirectory).isTrue()
        assertThat(name.substringBeforeLast("-")).isEqualTo("a" + "\u00e9".repeat(116))
    }

}
