package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.MaestroException
import maestro.device.Platform
import maestro.orchestra.debug.StepTraceEmitter
import maestro.orchestra.devicecore.AssertMode
import maestro.orchestra.devicecore.ChosenElement
import maestro.orchestra.devicecore.DeviceCoreTarget
import maestro.orchestra.devicecore.DeviceGateway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Task 1.2: a [MaestroException.NotImplemented] thrown by a command must never be laundered into a
 * warning by `optional` — it is caught as its own case BEFORE the optional check, always hard-stops
 * the flow, and is traced as a distinct OWED [maestro.orchestra.devicecore.Verdict.ERROR] record.
 */
class OrchestraNotImplementedWallTest {

    /** A gateway whose tap throws whatever `onTap` supplies; assert always passes. */
    private class ThrowingGateway(private val onTap: () -> Unit) : DeviceGateway {
        override fun connect(target: DeviceCoreTarget, appId: String?) {}
        override fun close() {}
        override fun launchApp(appId: String) {}
        override fun tap(selector: ElementSelector): ChosenElement? { onTap(); return null }
        override fun assertVisibility(selector: ElementSelector, mode: AssertMode, timeoutMs: Long): ChosenElement? = null
    }

    private fun tap(optional: Boolean) =
        MaestroCommand(tapOnElement = TapOnElementCommand(selector = ElementSelector(textRegex = "X", optional = optional)))
    private val leafAfter = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))

    @Test
    fun `NotImplemented hard-stops the flow even when the command is optional`() {
        val ran = mutableListOf<String>()
        val gw = ThrowingGateway { throw MaestroException.NotImplemented("device-core gateway does not yet implement tap") }
        // A CLI-style onCommandFailed that RETURNS a resolution (rather than the library default,
        // which rethrows) is what exercises "ignores the resolution": CONTINUE is what an optional
        // command's failure would normally get, and the wall must still hard-stop despite it.
        val orchestra = Orchestra(
            driver = gw, platform = Platform.ANDROID,
            onCommandComplete = { _, cmd -> if (cmd == leafAfter) ran += "ran" },
            onCommandFailed = { _, _, _ -> Orchestra.ErrorResolution.CONTINUE },
        )
        val result = runBlocking { orchestra.runFlow(listOf(tap(optional = true), leafAfter)) }

        assertThat(result.success).isFalse()   // hard-stop, not warn-and-continue
        assertThat(ran).isEmpty()              // the step AFTER the wall never runs
    }

    @Test
    fun `an optional element-not-found still warns and continues`() {
        val ran = mutableListOf<String>()
        val gw = ThrowingGateway {
            throw MaestroException.AssertionFailure(message = "not found", debugMessage = "d")
        }
        val orchestra = Orchestra(
            driver = gw, platform = Platform.ANDROID,
            onCommandComplete = { _, cmd -> if (cmd == leafAfter) ran += "ran" },
        )
        val result = runBlocking { orchestra.runFlow(listOf(tap(optional = true), leafAfter)) }

        assertThat(result.success).isTrue()    // unchanged: optional element-not-found is a warning
        assertThat(ran).containsExactly("ran") // the flow keeps going
    }

    @Test
    fun `the NotImplemented step is traced as ERROR with error type NotImplemented`(@TempDir dir: Path) {
        val trace = dir.resolve("steps.jsonl").toFile()
        val emitter = StepTraceEmitter(trace, backendId = "3x").also { it.openFor() }
        val gw = ThrowingGateway { throw MaestroException.NotImplemented("device-core gateway does not yet implement tap") }
        val orchestra = Orchestra(driver = gw, platform = Platform.ANDROID, stepTraceEmitter = emitter)

        // Non-optional, and the default onCommandFailed rethrows (like any other non-optional
        // MaestroException today) — the trace write happens before that rethrow escapes, which is
        // all this test cares about.
        runCatching { runBlocking { orchestra.runFlow(listOf(tap(optional = false))) } }
        emitter.close()

        val last = trace.readLines().last()
        val mapper = com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
        val node = mapper.readTree(last)
        assertThat(node.get("verdict").asText()).isEqualTo("ERROR")
        assertThat(node.get("error").get("type").asText()).isEqualTo("NotImplemented")
    }
}
