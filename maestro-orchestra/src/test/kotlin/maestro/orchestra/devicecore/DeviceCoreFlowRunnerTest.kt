package maestro.orchestra.devicecore

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.MaestroException
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import maestro.orchestra.InputTextCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.debug.StepTraceEmitter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DeviceCoreFlowRunnerTest {

    private class RecordingDriver : DeviceCoreDriver {
        val calls = mutableListOf<String>()
        override fun connect(target: DeviceCoreTarget, appId: String?) { calls += "connect" }
        override fun close() { calls += "close" }
        override fun launchApp(appId: String) { calls += "launch:$appId" }
        override fun tap(selector: ElementSelector): ChosenElement? { calls += "tap"; return null }
        override fun assertVisibility(selector: ElementSelector, mode: AssertMode): ChosenElement? {
            calls += "assert:$mode"; return null
        }
    }

    private fun cmd(c: Command) = MaestroCommand(c)

    @Test
    fun `runs the four supported commands in order`() {
        val d = RecordingDriver()
        DeviceCoreFlowRunner(d).run(
            listOf(
                cmd(LaunchAppCommand(appId = "com.example.example")),
                cmd(AssertConditionCommand(Condition(visible = ElementSelector(textRegex = "Form Test")))),
                cmd(TapOnElementCommand(selector = ElementSelector(textRegex = "Input/Keyboard"))),
                cmd(AssertConditionCommand(Condition(notVisible = ElementSelector(textRegex = "kwyjibo")))),
            ),
            config = null,
        )
        assertThat(d.calls).containsExactly(
            "launch:com.example.example", "assert:VISIBLE", "tap", "assert:NOT_VISIBLE",
        ).inOrder()
    }

    @Test
    fun `an unsupported command throws NotImplemented naming it`() {
        val d = RecordingDriver()
        val e = assertThrows<MaestroException.NotImplemented> {
            DeviceCoreFlowRunner(d).run(listOf(cmd(InputTextCommand(text = "hi"))), config = null)
        }
        assertThat(e.message).contains("InputText")
    }

    @Test
    fun `an unsupported LaunchAppCommand modifier throws NotImplemented naming it`() {
        val d = RecordingDriver()
        val e = assertThrows<MaestroException.NotImplemented> {
            DeviceCoreFlowRunner(d).run(
                listOf(cmd(LaunchAppCommand(appId = "com.example.example", clearState = true))),
                config = null,
            )
        }
        assertThat(e.message).contains("clearState")
    }

    @Test
    fun `an unsupported TapOnElementCommand modifier throws NotImplemented naming it`() {
        val d = RecordingDriver()
        val e = assertThrows<MaestroException.NotImplemented> {
            DeviceCoreFlowRunner(d).run(
                listOf(
                    cmd(
                        TapOnElementCommand(
                            selector = ElementSelector(textRegex = "Input/Keyboard"),
                            longPress = true,
                        ),
                    ),
                ),
                config = null,
            )
        }
        assertThat(e.message).contains("longPress")
    }

    @Test
    fun `an assert condition with neither visible nor notVisible throws NotImplemented`() {
        val d = RecordingDriver()
        val e = assertThrows<MaestroException.NotImplemented> {
            DeviceCoreFlowRunner(d).run(
                listOf(cmd(AssertConditionCommand(Condition(scriptCondition = "1 == 1")))),
                config = null,
            )
        }
        assertThat(e.message).contains("assert condition")
    }

    @Test
    fun `rethrows the failing command's exception after tracing FAIL`(@TempDir dir: File) {
        val d = RecordingDriver()
        val traceFile = File(dir, "steps.jsonl")
        val emitter = StepTraceEmitter(traceFile)
        emitter.openFor()

        assertThrows<MaestroException.NotImplemented> {
            DeviceCoreFlowRunner(d, emitter).run(
                listOf(cmd(InputTextCommand(text = "hi"))),
                config = null,
            )
        }
        emitter.close()

        val lines = traceFile.readLines()
        assertThat(lines).hasSize(1)
        val mapper = jacksonObjectMapper()
        val rec = mapper.readTree(lines[0])
        assertThat(rec.get("verdict").asText()).isEqualTo("FAIL")
    }

    @Test
    fun `emits a PASS trace line per successful step with backendId devicecore`(@TempDir dir: File) {
        val d = RecordingDriver()
        val traceFile = File(dir, "steps.jsonl")
        val emitter = StepTraceEmitter(traceFile)
        emitter.openFor()

        DeviceCoreFlowRunner(d, emitter).run(
            listOf(
                cmd(LaunchAppCommand(appId = "com.example.example")),
                cmd(TapOnElementCommand(selector = ElementSelector(idRegex = "fabAddIcon"))),
            ),
            config = null,
        )
        emitter.close()

        val lines = traceFile.readLines()
        assertThat(lines).hasSize(2)
        val mapper = jacksonObjectMapper()

        val rec0 = mapper.readTree(lines[0])
        assertThat(rec0.get("stepIndex").asInt()).isEqualTo(0)
        assertThat(rec0.get("backendId").asText()).isEqualTo("devicecore")
        assertThat(rec0.get("command").get("type").asText()).isEqualTo("LaunchAppCommand")
        assertThat(rec0.get("verdict").asText()).isEqualTo("PASS")

        val rec1 = mapper.readTree(lines[1])
        assertThat(rec1.get("stepIndex").asInt()).isEqualTo(1)
        assertThat(rec1.get("backendId").asText()).isEqualTo("devicecore")
        assertThat(rec1.get("command").get("type").asText()).isEqualTo("TapOnElementCommand")
        assertThat(rec1.get("command").get("selectorId").asText()).isEqualTo("fabAddIcon")
        assertThat(rec1.get("verdict").asText()).isEqualTo("PASS")
    }
}
