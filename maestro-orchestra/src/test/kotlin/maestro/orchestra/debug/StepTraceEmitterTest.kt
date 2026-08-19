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
}
