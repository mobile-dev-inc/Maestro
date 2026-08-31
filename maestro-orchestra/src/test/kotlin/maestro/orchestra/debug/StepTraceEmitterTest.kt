package maestro.orchestra.debug

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import maestro.orchestra.devicecore.ChosenElement
import maestro.orchestra.devicecore.Verdict
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class StepTraceEmitterTest {
    @Test
    fun `emits one jsonl record per step at the frozen schema`(@TempDir dir: File) {
        val f = File(dir, "steps.jsonl")
        val emitter = StepTraceEmitter(f, backendId = "devicecore")
        emitter.openFor()
        emitter.emit(0, "LaunchAppCommand", null, null, Verdict.PASS, null)
        emitter.emit(
            1, "TapOnElementCommand", null, "fabAddIcon", Verdict.PASS,
            ChosenElement(0, 0, 0, 0, 10, 20, null, "fabAddIcon", null),
        )
        emitter.close()

        val lines = f.readLines()
        assertThat(lines).hasSize(2)
        val mapper = jacksonObjectMapper()
        val rec0 = mapper.readTree(lines[0])
        assertThat(rec0.get("stepIndex").asInt()).isEqualTo(0)
        assertThat(rec0.get("backendId").asText()).isEqualTo("devicecore")
        assertThat(rec0.get("command").get("type").asText()).isEqualTo("LaunchAppCommand")
        assertThat(rec0.get("verdict").asText()).isEqualTo("PASS")
        assertThat(rec0.has("chosenElement")).isFalse()          // NON_NULL omits it
        assertThat(rec0.has("declined")).isFalse()                // dropped field
        val rec1 = mapper.readTree(lines[1])
        assertThat(rec1.get("chosenElement").get("centerY").asInt()).isEqualTo(20)
        assertThat(rec1.get("command").get("selectorId").asText()).isEqualTo("fabAddIcon")
    }

    @Test
    fun `emits an error object when a StepError is supplied and omits it otherwise`(@TempDir dir: File) {
        val f = File(dir, "steps.jsonl")
        val emitter = StepTraceEmitter(f, backendId = "3x")
        emitter.openFor()
        // step 0: no error -> "error" key absent
        emitter.emit(0, "TapOnElementCommand", "OK", null, Verdict.PASS, null, error = null)
        // step 1: NotImplemented OWED -> error present
        emitter.emit(
            1, "SetLocationCommand", null, null, Verdict.ERROR, null,
            error = StepTraceEmitter.StepError(type = "NotImplemented", message = "device-core gateway does not yet implement setLocation"),
        )
        emitter.close()

        val lines = f.readLines()
        val mapper = jacksonObjectMapper()
        val step0 = mapper.readTree(lines[0])
        val step1 = mapper.readTree(lines[1])

        assertThat(step0.has("error")).isFalse()          // omitted when null (byte-identical to legacy)
        assertThat(step1.get("verdict").asText()).isEqualTo("ERROR")
        assertThat(step1.get("error").get("type").asText()).isEqualTo("NotImplemented")
        assertThat(step1.get("error").get("message").asText())
            .isEqualTo("device-core gateway does not yet implement setLocation")
    }
}
