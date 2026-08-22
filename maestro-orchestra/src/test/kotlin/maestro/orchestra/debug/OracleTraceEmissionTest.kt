package maestro.orchestra.debug

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.Maestro
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.Platform
import maestro.orchestra.EvalScriptCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Task 2.1 parity check: on `main`'s legacy engine, a clean step must emit the same field set the
 * 3.x/device-core side writes — stepIndex/backendId/command/verdict, with chosenElement and error
 * both omitted (NON_NULL) when there's nothing to report. Proves the 2x oracle's `steps.jsonl` is
 * byte-identical in shape to the 3.x trace for the passing case, before any backend-specific
 * behavior (element resolution, failures) is layered on.
 */
class OracleTraceEmissionTest {

    private fun mockMaestro(): Maestro = mockk(relaxed = true) {
        coEvery { viewHierarchy(any()) } returns ViewHierarchy(TreeNode(attributes = mutableMapOf()))
        coEvery { cachedDeviceInfo } returns DeviceInfo(
            platform = Platform.ANDROID,
            widthPixels = 100,
            heightPixels = 200,
            widthGrid = 100,
            heightGrid = 200,
        )
    }

    @Test
    fun `a clean step emits stepIndex, backendId, command, verdict and no error`(@TempDir dir: File) {
        val traceFile = File(dir, "steps.jsonl")
        val emitter = StepTraceEmitter(traceFile, backendId = "2x").also { it.openFor() }
        val orchestra = Orchestra(maestro = mockMaestro(), stepTraceEmitter = emitter)

        runBlocking {
            orchestra.runFlow(listOf(MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))))
        }
        emitter.close()

        val line = traceFile.readLines().single()
        val node = jacksonObjectMapper().readTree(line)

        assertThat(node.fieldNames().asSequence().toList())
            .containsExactly("stepIndex", "backendId", "command", "verdict")
        assertThat(node.get("stepIndex").asInt()).isEqualTo(0)
        assertThat(node.get("backendId").asText()).isEqualTo("2x")
        assertThat(node.get("command").get("type").asText()).isEqualTo("EvalScriptCommand")
        assertThat(node.get("verdict").asText()).isEqualTo("PASS")
        assertThat(node.has("error")).isFalse()
        assertThat(node.has("chosenElement")).isFalse()
    }
}
