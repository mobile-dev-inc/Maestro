package maestro.orchestra.debug

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.Bounds
import maestro.DeviceInfo
import maestro.FindElementResult
import maestro.Maestro
import maestro.ScreenRecording
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.Platform
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.backend.BackendContext
import maestro.orchestra.backend.ChosenElement
import maestro.orchestra.backend.CommandExecutionResult
import maestro.orchestra.backend.ExecutionBackend
import maestro.orchestra.backend.StepTrace
import maestro.orchestra.backend.Verdict
import okio.Sink
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class StepTraceEmitterTest {

    @TempDir
    lateinit var tempDir: Path

    /**
     * Fakes the seam: [execute] hands back exactly the trace the emitter is meant to observe, so the
     * test drives the emitter without any real device. TapOnElement resolves an element (non-null
     * [ChosenElement]); everything else resolves none (null). Records what it was handed.
     */
    private class FakeBackend : ExecutionBackend {
        val executed = mutableListOf<Command>()

        override fun open(appId: String?) {}
        override fun close() {}

        override suspend fun execute(command: Command, context: BackendContext): CommandExecutionResult {
            executed.add(command)
            val chosen = if (command is TapOnElementCommand) {
                ChosenElement(
                    x = 10, y = 20, width = 100, height = 40,
                    centerX = 60, centerY = 40,
                    text = "Login", resourceId = "com.example:id/login", index = null,
                )
            } else null
            return CommandExecutionResult(
                mutating = true,
                trace = StepTrace(verdict = Verdict.PASS, chosenElement = chosen),
            )
        }

        override fun hierarchySnapshot(): TreeNode? = TreeNode()

        override suspend fun findElement(
            selector: ElementSelector,
            optional: Boolean,
            timeoutMs: Long?,
            context: BackendContext,
        ): FindElementResult = error("findElement is not exercised by StepTraceEmitterTest")

        override suspend fun evaluateCondition(
            condition: Condition?,
            commandOptional: Boolean,
            timeoutMs: Long?,
            context: BackendContext,
        ): Boolean = error("evaluateCondition is not exercised by StepTraceEmitterTest")

        override val deviceInfo: DeviceInfo
            get() = DeviceInfo(
                platform = Platform.ANDROID,
                widthPixels = 1080,
                heightPixels = 1920,
                widthGrid = 1080,
                heightGrid = 1920,
            )

        override suspend fun takeScreenshot(out: Sink, compressed: Boolean, bounds: Bounds?) =
            error("takeScreenshot is not exercised by StepTraceEmitterTest")

        override suspend fun startScreenRecording(out: Sink): ScreenRecording =
            error("startScreenRecording is not exercised by StepTraceEmitterTest")

        override fun setAndroidChromeDevToolsEnabled(enabled: Boolean) {}
    }

    private val mapper = jacksonObjectMapper()

    private fun flow() = listOf(
        MaestroCommand(tapOnElement = TapOnElementCommand(selector = ElementSelector(textRegex = "Login"))),
        MaestroCommand(launchAppCommand = LaunchAppCommand(appId = "com.example.app")),
    )

    @Test
    fun `enabled - writes one JSONL record per step with verdict and chosenElement`() {
        val traceFile = tempDir.resolve("trace/steps.jsonl").toFile()
        val backend = FakeBackend()
        val orchestra = Orchestra(
            maestro = mockk<Maestro>(relaxed = true),
            backend = backend,
            stepTraceEmitter = StepTraceEmitter(traceFile),
        )

        runBlocking { orchestra.runFlow(flow()) }

        assertThat(traceFile.exists()).isTrue()
        val lines = traceFile.readLines().filter { it.isNotBlank() }
        assertThat(lines).hasSize(2)

        val first = mapper.readValue<Map<String, Any?>>(lines[0])
        assertThat(first["stepIndex"]).isEqualTo(0)
        assertThat(first["backendId"]).isEqualTo("legacy")
        assertThat(first["verdict"]).isEqualTo("PASS")
        @Suppress("UNCHECKED_CAST")
        val command = first["command"] as Map<String, Any?>
        assertThat(command["type"]).isEqualTo("TapOnElementCommand")
        assertThat(command["selectorText"]).isEqualTo("Login")
        @Suppress("UNCHECKED_CAST")
        val chosen = first["chosenElement"] as Map<String, Any?>
        assertThat(chosen["x"]).isEqualTo(10)
        assertThat(chosen["y"]).isEqualTo(20)
        assertThat(chosen["width"]).isEqualTo(100)
        assertThat(chosen["height"]).isEqualTo(40)
        assertThat(chosen["centerX"]).isEqualTo(60)
        assertThat(chosen["centerY"]).isEqualTo(40)
        assertThat(chosen["text"]).isEqualTo("Login")
        assertThat(chosen["resourceId"]).isEqualTo("com.example:id/login")

        val second = mapper.readValue<Map<String, Any?>>(lines[1])
        assertThat(second["stepIndex"]).isEqualTo(1)
        assertThat(second["verdict"]).isEqualTo("PASS")
        @Suppress("UNCHECKED_CAST")
        val secondCommand = second["command"] as Map<String, Any?>
        assertThat(secondCommand["type"]).isEqualTo("LaunchAppCommand")
        assertThat(second["chosenElement"]).isNull()
    }

    @Test
    fun `disabled - writes nothing and dispatch is unchanged`() {
        val traceDir = tempDir.resolve("trace")
        val backend = FakeBackend()
        val orchestra = Orchestra(
            maestro = mockk<Maestro>(relaxed = true),
            backend = backend,
            stepTraceEmitter = null,
        )

        runBlocking { orchestra.runFlow(flow()) }

        // No file written.
        assertThat(Files.exists(traceDir)).isFalse()
        // Dispatch unchanged: every command still reached the backend, in order.
        assertThat(backend.executed).hasSize(2)
        assertThat(backend.executed[0]).isInstanceOf(TapOnElementCommand::class.java)
        assertThat(backend.executed[1]).isInstanceOf(LaunchAppCommand::class.java)
    }
}
