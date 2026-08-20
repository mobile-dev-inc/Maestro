package maestro.orchestra.flow

import com.google.common.truth.Truth.assertThat
import maestro.MaestroException
import maestro.orchestra.devicecore.DeviceCoreEvidence
import maestro.orchestra.devicecore.FakeDeviceProvider
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tier-2 recovery of the deleted `maestro-test` `IntegrationTest`'s JS flow-control family (cases
 * 052, 063, 064, 066, 067 (pass/fail), 070, 092, 102, 128 — see git history at
 * c1538ee7^:maestro-test/src/test/kotlin/maestro/test/IntegrationTest.kt). Case 093 (JS default
 * values) was already recovered in `FlowMatrixVariablesTest` — not duplicated here.
 *
 * GROUND TRUTH (verified empirically against [maestro.orchestra.devicecore.DeviceGateway] and
 * `Orchestra.kt`): `evalScript`/`runScript`/`assertTrue` (a scriptCondition) never touch the
 * gateway at all — they run entirely inside the JS engine (`evaluateCondition`'s
 * `condition.scriptCondition` branch, and `evalScriptCommand`/`runScriptCommand`, never call
 * `driver.*`). `inputText` (and its `inputRandomText`/`inputRandomNumber`/`inputRandomEmail`/
 * `inputRandomPersonName` callers, all routed through `inputTextCommand` -> `driver.inputText`)
 * is a declared-but-unbuilt roadmap verb (`DeviceGateway.inputText`) that throws
 * [MaestroException.NotImplemented]. `copyTextFrom` is even more unwired than that:
 * `copyTextFromCommand` (Orchestra.kt:1447) calls `driver.hierarchy()` UNCONDITIONALLY (it never
 * reads a selector or touches evidence at all — hierarchy/dump has no device-core type yet), so no
 * matched-text ever reaches `maestro.copiedText`, regardless of what the provider's evidence says.
 *
 * Cases 052/063/064/066/070/102 all reach an unwired verb as either their first command or their
 * first command after a JS-engine-only step (evalScript/runScript produce no seam-observable state
 * -- no tap, no launch, no log -- so there is nothing for an `assertThrows`-only test to
 * discriminate). Disabled instead of a false-green test, per the same reasoning as
 * `FlowMatrixVariablesTest`'s inputText-blocked cases and `FlowMatrixRetryTest`'s Case 082.
 */
class FlowMatrixJsTest {

    // 052's five commands are inputRandomText/inputRandomNumber/inputRandomEmail/inputRandomPersonName
    // -- all routed through inputTextCommand -> driver.inputText, unwired -- and there is no
    // launchApp/tap/assertVisibility before the first one, so there is no seam-observable state to
    // assert before the throw. An assertThrows-only test can't discriminate "random text/number/email/
    // personName generation is correct" from any other broken wiring. Disabled instead of a false-green
    // test.
    @Test
    @Disabled(
        "blocked on device-core inputText verb — 052's first command is inputRandomText itself, " +
            "leaving no seam-observable state to assert the random-value generation; see spec §5"
    )
    fun `Case 052 - Input random`() {
        // TODO(inputText): original IntegrationTest (readCommands("052_text_random")) asserted, over
        // the driver's InputText events: every value length >= 5, at least one InputText parses as an
        // Int in 10000..99999 (inputRandomNumber length:5), at least one contains "@" (inputRandomEmail),
        // at least one contains " " (inputRandomPersonName).
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("052_text_random")
        }
        assertThat(exception.message).contains("inputText")
    }

    // 063 is five inputText commands back to back, no wired verb anywhere in the fixture -- the very
    // first command throws, leaving nothing to assert but the throw itself.
    @Test
    @Disabled(
        "blocked on device-core inputText verb — 063's first (and only) commands are inputText, " +
            "leaving no seam-observable state to assert JS-injection/interpolation behavior; see spec §5"
    )
    fun `Case 063 - Javascript injection`() {
        // TODO(inputText): original IntegrationTest (readCommands("063_js_injection"), env A=1 B=2)
        // asserted driver.assertEvents(listOf(InputText("1"), InputText("2"), InputText("12"),
        // InputText("3"), InputText("\${A} \${B} 1 2"))) -- proving raw JS expression eval
        // (${A + B} == "12" string concat, not numeric add), parseInt coercion (${parseInt(A) +
        // parseInt(B)} == "3"), and escaped-vs-live interpolation (\${A} \${B} ${A} ${B}) all work.
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("063_js_injection")
        }
        assertThat(exception.message).contains("inputText")
    }

    // 064's first command is runScript (in-engine, no seam call), but its SECOND command is
    // inputText: ${output.sharedResult} -- unwired, and runScript's own effect (setting `output.*`
    // JS variables) is not observable via the provider or via onLog (064_script.js never calls
    // console.log). No seam-observable state survives to the throw.
    @Test
    @Disabled(
        "blocked on device-core inputText verb — 064's second command (the first inputText, right " +
            "after a non-logging runScript) throws before any seam-observable state exists to assert " +
            "runScript file-loading / parameter-passing / MAESTRO_FILENAME behavior; see spec §5"
    )
    fun `Case 064 - Javascript files`() {
        // TODO(inputText): original IntegrationTest (readCommands("064_js_files")) asserted, over nine
        // InputText events: "Main"/"Sub"/"Sub"/"Main"/"Sub" (output.sharedResult set by 064_script.js
        // then overwritten by 064_subflow.yaml's runScript: 064_script_alt.js, then read back after
        // the subflow returns showing the OUTER scope's value survived), "064_js_files" (MAESTRO_FILENAME
        // inside runScript), "Hello, Input Parameter!"/"Hello, Evaluated Parameter!" (runScript with
        // env: parameter, literal then ${...}-evaluated), "064_js_files" again (MAESTRO_FILENAME of the
        // outer flow, proving the subflow's own MAESTRO_FILENAME didn't leak).
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("064_js_files")
        }
        assertThat(exception.message).contains("inputText")
    }

    // SPECIAL case (see task brief): copyTextFromCommand (Orchestra.kt:1447) calls driver.hierarchy()
    // UNCONDITIONALLY -- it never translates the `id: Field` selector or reads any ElementEvidence at
    // all, so seeding FakeDeviceProvider with DeviceCoreEvidence.resolvedVisible(...) (matched text
    // "Maestro") makes no difference: the copied text can never reach maestro.copiedText / the
    // subsequent inputText, regardless of what the provider's evidence says. Confirmed by reading the
    // command implementation, not assumed. Genuinely the unwired-with-no-observable-antecedent case --
    // hierarchy() is the very first (and only) seam call the command makes.
    @Test
    @Disabled(
        "blocked on device-core hierarchy() verb — copyTextFromCommand calls driver.hierarchy() " +
            "unconditionally (Orchestra.kt:1447), never reading selector/evidence at all, so no seeded " +
            "provider evidence can make the copied text observable; see spec §5"
    )
    fun `Case 066 - Copy text into JS variable`() {
        // TODO(hierarchy): original IntegrationTest (readCommands("066_copyText_jsVar"), driver screen
        // with element id="Field" text="Maestro") asserted
        // driver.assertEvents(listOf(Event.InputText("Hello, Maestro"))) -- copyTextFrom { id: Field }
        // then inputText: ${'Hello, ' + maestro.copiedText}.
        val provider = FakeDeviceProvider { DeviceCoreEvidence.resolvedVisible(it.toString()) }
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("066_copyText_jsVar", provider = provider)
        }
        assertThat(exception.message).contains("hierarchy")
    }

    @Test
    fun `Case 067 - Assert True - Pass`() {
        // assertTrue: ${1+1} -- a scriptCondition, evaluated entirely by evaluateCondition's
        // condition.scriptCondition branch (Orchestra.kt:1021), which never calls driver.*. "2" is
        // truthy (non-blank, not "false"/"undefined"/"null", not numeric 0.0), so the assert passes.
        val result = FlowMatrix.run("067_assertTrue_pass")
        assertThat(result.success).isTrue()
    }

    @Test
    fun `Case 067 - Assert True - Fail`() {
        // assertTrue: ${1-1} -- scriptCondition "0" parses as numeric 0.0, evaluateCondition returns
        // false, assertConditionCommand throws AssertionFailure. Same as the original's assertThrows.
        assertThrows<MaestroException.AssertionFailure> {
            FlowMatrix.run("067_assertTrue_fail")
        }
    }

    // 070 is evalScript (in-engine, no seam call, and this fixture's scripts never console.log, so
    // no onLog signal either) then inputText -- unwired, and the first command's success leaves
    // nothing observable via provider or logs before the throw.
    @Test
    @Disabled(
        "blocked on device-core inputText verb — 070's evalScript commands produce no seam-observable " +
            "state (no tap/launch/log) before the first inputText throws; see spec §5"
    )
    fun `Case 070 - Evaluate JS inline`() {
        // TODO(inputText): original IntegrationTest (readCommands("070_evalScript")) asserted
        // driver.assertEvents(listOf(Event.InputText("2"), Event.InputText("Result is: 2"))) --
        // evalScript: ${output.number = 1 + 1}, evalScript: "${output.text = 'Result is: ' + output.number}",
        // inputText: ${output.number}, inputText: ${output.text}.
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("070_evalScript")
        }
        assertThat(exception.message).contains("inputText")
    }

    @Test
    fun `Case 092 - Log messages`() {
        val logs = mutableListOf<String>()
        val result = FlowMatrix.run("092_log_messages", onLog = { logs += it })
        assertThat(result.success).isTrue()
        // old: assertThat(receivedLogs).containsExactly("Log from evalScript", "Log from runScript").inOrder()
        assertThat(logs).containsExactly("Log from evalScript", "Log from runScript").inOrder()
    }

    // Case 093 - JS default values was already recovered in FlowMatrixVariablesTest -- skipped here
    // to avoid duplicating the same fixture/assertion.

    // 102's first command is inputText: ${null ?? 'foo'} (exercising GraalJS's ES2020 ?? operator) --
    // unwired, and it's the flow's very first command, so there's no seam-observable state at all.
    @Test
    @Disabled(
        "blocked on device-core inputText verb — 102's first command is inputText itself, leaving no " +
            "seam-observable state to assert the GraalJS ?? operator evaluated correctly; see spec §5"
    )
    fun `Case 102 - GraalJs config`() {
        // TODO(inputText): original IntegrationTest (readCommands("102_graaljs")) asserted
        // driver.assertEvents(listOf(Event.InputText("foo"), Event.InputText("bar"))) --
        // inputText: ${null ?? 'foo'}, then runFlow: 102_graaljs_subflow.yaml's own
        // inputText: ${null ?? 'bar'}, both under jsEngine: graaljs (top-level config inherited by the
        // subflow).
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("102_graaljs")
        }
        assertThat(exception.message).contains("inputText")
    }

    @Test
    fun `Case 128 - Random Data Generation`() {
        // evalScript (datafaker via GraalJS) + assertTrue -- both scriptCondition/JS-engine-only, no
        // seam call at all. Original: "Should succeed - uses positive assertions to verify engine runs
        // and validates data" (no explicit driver-event assertion beyond a clean run).
        val result = FlowMatrix.run("128_datafaker_graaljs")
        assertThat(result.success).isTrue()
    }
}
