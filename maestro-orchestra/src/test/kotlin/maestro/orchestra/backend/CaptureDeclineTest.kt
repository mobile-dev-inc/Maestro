package maestro.orchestra.backend

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.ScreenRecording
import maestro.TreeNode
import maestro.ai.AIPredictionEngine
import maestro.ai.cloud.Defect
import maestro.orchestra.AssertNoDefectsWithAICommand
import maestro.orchestra.AssertScreenshotCommand
import maestro.orchestra.AssertWithAICommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.ExtractTextWithAICommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.Orchestra
import maestro.orchestra.StartRecordingCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.debug.StepTraceEmitter
import okio.Sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * Task 4.1b — Orchestra's 6 above-seam capture handlers (assertNoDefectsWithAICommand,
 * assertWithAICommand, extractTextWithAICommand, assertScreenshotCommand, takeScreenshotCommand,
 * startRecordingCommand) must SKIP cleanly (return non-mutating, no crash, no StepTrace payload)
 * when the backend declines the underlying takeScreenshot/startScreenRecording call with
 * [BackendUnsupportedOperation] — device-core's signal that it has no screenshot/recording verb.
 *
 * [LegacyExecutionBackend] never throws [BackendUnsupportedOperation], so these catches are dead
 * code on the legacy path; this test proves only the device-core-facing behavior.
 */
class CaptureDeclineTest {

    /** Declines every capture verb; every other member errors loudly if a test path reaches it. */
    private class DecliningBackend : ExecutionBackend {
        override val backendId: String = "legacy"

        override fun open(appId: String?, config: MaestroConfig?) {}
        override fun close() {}

        override suspend fun execute(command: Command, context: BackendContext): CommandExecutionResult =
            error("execute is not exercised by CaptureDeclineTest")

        override fun hierarchySnapshot(): TreeNode? = TreeNode()

        override suspend fun evaluateCondition(
            condition: Condition?,
            commandOptional: Boolean,
            timeoutMs: Long?,
            context: BackendContext,
        ): Boolean = error("evaluateCondition is not exercised by CaptureDeclineTest")

        override suspend fun takeScreenshot(
            out: Sink,
            compressed: Boolean,
            cropOn: ElementSelector?,
            optional: Boolean,
            context: BackendContext?,
        ) {
            throw BackendUnsupportedOperation("device-core has no screenshot/recording verb")
        }

        override suspend fun startScreenRecording(out: Sink): ScreenRecording {
            throw BackendUnsupportedOperation("device-core has no screenshot/recording verb")
        }
    }

    /** Fails the test if the AI engine is ever invoked — the screenshot is its only input, so a
     * declined screenshot must short-circuit before the engine is called at all. */
    private class FailingAIPredictionEngine : AIPredictionEngine {
        override suspend fun findDefects(screen: ByteArray): List<Defect> =
            error("AI engine must not be called when the screenshot was declined")

        override suspend fun performAssertion(screen: ByteArray, assertion: String): Defect? =
            error("AI engine must not be called when the screenshot was declined")

        override suspend fun extractText(screen: ByteArray, query: String): String =
            error("AI engine must not be called when the screenshot was declined")
    }

    private val mapper = jacksonObjectMapper()

    private fun buildOrchestra(traceFile: File): Orchestra {
        val fakeMaestro: Maestro = mockk(relaxed = true)
        return Orchestra(
            maestro = fakeMaestro,
            backend = DecliningBackend(),
            stepTraceEmitter = StepTraceEmitter(traceFile),
            AIPredictionEngine = FailingAIPredictionEngine(),
        )
    }

    /** Runs [command] alone as a flow and returns (flow success, the single trace-file line, if any). */
    private fun runDeclined(command: Command, tempDir: Path): Pair<Boolean, String?> {
        val traceFile = tempDir.resolve("step-trace.jsonl").toFile()
        val orchestra = buildOrchestra(traceFile)
        val result = runBlocking { orchestra.runFlow(listOf(MaestroCommand(command))) }
        val lines = if (traceFile.exists()) traceFile.readLines().filter { it.isNotBlank() } else emptyList()
        return result.success to lines.singleOrNull()
    }

    /** The trace line for a skipped step must look exactly like any other above-seam PASS: no
     * `chosenElement` payload (the backend's declined StepTrace, if any, must never reach the emitter). */
    private fun assertNoStepTracePayload(line: String?) {
        assertThat(line).isNotNull()
        val node = mapper.readTree(line)
        assertThat(node.get("verdict").asText()).isEqualTo("PASS")
        assertThat(node.has("chosenElement")).isFalse()
    }

    @Test
    fun `assertNoDefectsWithAICommand skips when backend declines takeScreenshot`(@TempDir tempDir: Path) {
        val command = AssertNoDefectsWithAICommand()
        val (success, line) = runDeclined(command, tempDir)
        assertThat(success).isTrue()
        assertNoStepTracePayload(line)
    }

    @Test
    fun `assertWithAICommand skips when backend declines takeScreenshot`(@TempDir tempDir: Path) {
        val command = AssertWithAICommand(assertion = "the screen shows a login button")
        val (success, line) = runDeclined(command, tempDir)
        assertThat(success).isTrue()
        assertNoStepTracePayload(line)
    }

    @Test
    fun `extractTextWithAICommand skips when backend declines takeScreenshot`(@TempDir tempDir: Path) {
        val command = ExtractTextWithAICommand(query = "what does the button say", outputVariable = "OUT")
        val (success, line) = runDeclined(command, tempDir)
        assertThat(success).isTrue()
        assertNoStepTracePayload(line)
    }

    @Test
    fun `assertScreenshotCommand skips when backend declines takeScreenshot`(@TempDir tempDir: Path) {
        // A pre-existing reference screenshot must exist so the command reaches backend.takeScreenshot
        // rather than throwing "file not found" first; its contents are irrelevant — the compare never
        // runs once the decline is caught.
        tempDir.resolve("shot.png").toFile().writeBytes(byteArrayOf(0))
        val command = AssertScreenshotCommand(
            path = "shot",
            thresholdPercentage = "90",
            flowPath = tempDir,
        )
        val (success, line) = runDeclined(command, tempDir)
        assertThat(success).isTrue()
        assertNoStepTracePayload(line)
    }

    @Test
    fun `takeScreenshotCommand skips when backend declines takeScreenshot`(@TempDir tempDir: Path) {
        // Absolute path (no artifactsDir bundle) so the fallback File("$path.png") writes inside the
        // temp dir instead of the test process's working directory.
        val command = TakeScreenshotCommand(path = tempDir.resolve("out").toString())
        val (success, line) = runDeclined(command, tempDir)
        assertThat(success).isTrue()
        assertNoStepTracePayload(line)
    }

    @Test
    fun `startRecordingCommand skips when backend declines startScreenRecording`(@TempDir tempDir: Path) {
        val command = StartRecordingCommand(path = tempDir.resolve("out").toString())
        val (success, line) = runDeclined(command, tempDir)
        assertThat(success).isTrue()
        assertNoStepTracePayload(line)
    }
}
