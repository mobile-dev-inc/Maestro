package maestro.orchestra.flow

import com.google.common.truth.Truth.assertThat
import maestro.MaestroException
import maestro.orchestra.devicecore.DeviceCoreEvidence
import maestro.orchestra.devicecore.FakeDeviceProvider
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tier-2 recovery of the deleted `maestro-test` `IntegrationTest`'s runFlow/subflow/hooks family
 * (cases 046, 047, 094, 103-109 — see git history at
 * c1538ee7^:maestro-test/src/test/kotlin/maestro/test/IntegrationTest.kt).
 *
 * GROUND TRUTH (see task-7/8 reports): [maestro.orchestra.devicecore.DeviceGateway] only wires
 * `connect`/`close`/`launchApp`/`tap`/`assertVisibility` to device-core; every other verb
 * (`inputText` included) throws [MaestroException.NotImplemented], which the default
 * `onCommandFailed` (unset by [FlowMatrix]) rethrows out of `Orchestra.runFlow` entirely, rather
 * than folding into `FlowResult.success = false`. Two things are ALSO fully in-engine and never
 * touch the [FakeDeviceProvider] seam at all, wired or not: `runScript`/`evalScript` (pure JS
 * execution) and a scripted `assertTrue`/`when: true:` condition with no `visible`/`notVisible`
 * clause (evaluateCondition short-circuits on the script check before any device query) — both
 * recover fully faithfully with no dependency on which DeviceGateway verbs are wired yet.
 *
 * `tapOn <selector>` (TapOnElementCommand) calls `driver.tap(selector)` directly with no prior
 * `assertVisibility` — device-core resolves the element itself. [FakeDeviceProvider]'s `tap()`
 * always reports `Outcome.Acted` unless a test overrides `tapOutcome`, so a bare `tapOn` succeeds
 * under the default absent-evidence provider (evidence only gates `assertVisibility`/`inspect`).
 * `tapOn point:` (TapOnPointV2Command) is a different code path (`driver.tapOnPoint`), which is NOT
 * wired.
 */
class FlowMatrixRunFlowTest {

    @Test
    fun `Case 046 - Run flow`() {
        // 046_run_flow.yaml -> runFlow: 013_launch_app.yaml (launchApp, wired) then tapOn "Primary
        // button" (driver.tap, wired — succeeds regardless of evidence, see class doc). Fully
        // recovers the original's "no test failure" shape.
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent(it.toString()) }
        val result = FlowMatrix.run("046_run_flow", provider = provider)
        assertThat(result.success).isTrue()
        assertThat(provider.launchedApps).isNotEmpty()  // the sub-flow's launchApp reached the seam
        // old: driver.assertEvents([LaunchApp("com.example.app"), Tap(Point(50,50))])
        assertThat(provider.launchedApps).containsExactly("com.example.app")
        assertThat(provider.tapCount).isEqualTo(1)
    }

    @Test
    fun `Case 047 - Nested run flow`() {
        // 047_run_flow_nested.yaml -> runFlow: 046_run_flow.yaml (itself runFlow 013_launch_app.yaml
        // + tapOn "Primary button") then tapOn "Secondary Button" at the outer level. Both taps and
        // the single launchApp are wired; fully recovers the original's "no test failure" shape.
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent(it.toString()) }
        val result = FlowMatrix.run("047_run_flow_nested", provider = provider)
        assertThat(result.success).isTrue()
        // old: driver.assertEvents([LaunchApp("com.example.app"), Tap(50,50), Tap(100,100)])
        assertThat(provider.launchedApps).containsExactly("com.example.app")
        assertThat(provider.tapCount).isEqualTo(2)
    }

    // Fix round 1: 094's ENTIRE flow body is a single inline `runFlow: { env:, commands: [inputText:
    // ${INNER_ENV}] }` command -- inputText is not yet a wired DeviceGateway verb, and there is no
    // command before it (no launchApp/tap/assert), so there is zero provider-observable state to
    // assert before the NotImplemented throw. An assertThrows-only test would pass whether or not
    // the inline runFlow's env-block interpolation (the entire point of this case) is correct.
    // Disabled instead of a false-green test.
    @Test
    @Disabled(
        "blocked on device-core inputText verb — no seam-observable state to assert inline runFlow " +
            "env-block interpolation until inputText is wired; see spec §5"
    )
    fun `Case 094 - Subflow with inlined commands`() {
        // TODO(inputText): original IntegrationTest (readCommands("094_runFlow_inline"), empty
        // screen) ran an inline `runFlow: { env: { INNER_ENV: Inner Parameter }, commands: [inputText:
        // ${INNER_ENV}] }` and asserted:
        //   driver.assertHasEvent(Event.InputText("Inner Parameter"))
        // proving the inline runFlow's own `env:` block is visible to its inline `commands:`.
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("094_runFlow_inline")
        }
        assertThat(exception.message).contains("inputText")
    }

    @Test
    fun `Case 103 - execute onFlowStart and onFlowComplete hooks`() {
        // onFlowStart: [runScript(103_setup.js) -> logs "setup", inputText("test1")]; onFlowComplete:
        // [runScript(103_teardown.js) -> logs "teardown", inputText("test2")]. Both runScripts are
        // pure JS (wired-independent) and log before their sibling inputText throws NotImplemented;
        // the onFlowStart failure is what ultimately propagates out of runFlow (onFlowComplete's own
        // inputText failure doesn't override it -- Orchestra.kt:264-271). Recovers the "hooks ran, in
        // order" shape faithfully via the pre-throw logs; old body-tap assertion
        // (Event.Tap(Point(100,200))) is unreachable since the main flow's `tapOn: point:` never runs
        // (onStartSuccess never becomes true).
        val logs = mutableListOf<String>()
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("103_on_flow_start_complete_hooks", onLog = { logs.addAll(it) })
        }
        assertThat(exception.message).contains("inputText")
        // old: receivedLogs containsExactly("setup", "teardown").inOrder()
        assertThat(logs).containsExactly("setup", "teardown").inOrder()
    }

    // Fix round 1: 104's onFlowStart hook is `inputText: "test1"` -- not yet wired, and it's the
    // FIRST command in the flow (before the main body's `assertVisible: id: element_id`, which is
    // the case's actual point: flow fails, but both hooks still run around the failure). With
    // inputText unwired, the flow never reaches the main body's assertVisible at all -- there is no
    // provider-observable state (no log, no tap, no launch) before the inputText NotImplemented
    // throws. An assertThrows-only test can't discriminate this from any other broken wiring.
    // Disabled instead of a false-green test.
    @Test
    @Disabled(
        "blocked on device-core inputText verb — no seam-observable state to assert hooks-run-around" +
            "-a-failed-flow-body until inputText is wired; see spec §5"
    )
    fun `Case 104 - execute onFlowStart and onFlowComplete hooks when flow failed`() {
        // TODO(inputText): original IntegrationTest (readCommands("104_on_flow_start_complete_hooks_flow_failed"),
        // driver has element id="another_id", NOT "element_id") ran onFlowStart: [inputText("test1")],
        // main body: [assertVisible: id: element_id] (fails, element absent), onFlowComplete:
        // [inputText("test2")], and asserted:
        //   assertThrows<MaestroException.AssertionFailure> { ...runFlow(commands)... }
        //   driver.assertEvents([InputText("test1"), InputText("test2")])
        // i.e. both hooks run to completion around the main body's AssertionFailure, and that
        // AssertionFailure (not a hook failure) is what ultimately propagates.
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent(it.toString()) }
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("104_on_flow_start_complete_hooks_flow_failed", provider = provider)
        }
        assertThat(exception.message).contains("inputText")
    }

    @Test
    fun `Case 105 - execute onFlowStart and onFlowComplete when js output is set`() {
        // onFlowStart: [runScript(105_setup.js) sets output.setup_result = "setup"]; main body:
        // [evalScript logs output.setup_result -> "setup"]; onFlowComplete: [runScript(105_teardown.js)
        // sets output.teardown_result = "teardown", evalScript logs output.teardown_result ->
        // "teardown"]. Every command here is pure JS-engine (runScript/evalScript) -- no
        // DeviceGateway verb touched at all -- so this recovers fully faithfully, exactly as
        // originally written.
        val logs = mutableListOf<String>()
        val result = FlowMatrix.run(
            "105_on_flow_start_complete_when_js_output_set",
            onLog = { logs.addAll(it) },
        )
        assertThat(result.success).isTrue()
        assertThat(logs).containsExactly("setup", "teardown").inOrder()
    }

    @Test
    fun `Case 106 - execute onFlowStart and onFlowComplete when js output is set with subflows`() {
        // Main body: runFlow(106_subflow.yaml) [onFlowStart: runScript(106_setup.js) sets
        // output.setup_subflow_result; body: evalScript logs "subflow"; onFlowComplete:
        // runScript(106_teardown.js) sets output.teardown_subflow_result], then two outer evalScripts
        // logging output.setup_subflow_result / output.teardown_subflow_result. All pure JS-engine;
        // fully recovers the original's log order.
        val logs = mutableListOf<String>()
        val result = FlowMatrix.run(
            "106_on_flow_start_complete_when_js_output_set_subflows",
            onLog = { logs.addAll(it) },
        )
        assertThat(result.success).isTrue()
        assertThat(logs).containsExactly("subflow", "setup subflow", "teardown subflow").inOrder()
    }

    @Test
    fun `Case 107 - execute defineVariablesCommand before onFlowStart and onFlowComplete are executed`() {
        // appId: ${MAESTRO_APP_ID} (env: MAESTRO_APP_ID: com.example.app); onFlowStart:
        // [launchApp(${MAESTRO_APP_ID})] -- launchApp IS wired; main body: [evalScript logs
        // MAESTRO_APP_ID]. Proves the env-defined variable is visible both to the appId header and to
        // onFlowStart, which only works if DefineVariablesCommand runs before the hooks. Fully
        // recovers the original's success + log + launchedApps assertions.
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent(it.toString()) }
        val logs = mutableListOf<String>()
        val result = FlowMatrix.run(
            "107_define_variables_command_before_hooks",
            provider = provider,
            onLog = { logs.addAll(it) },
        )
        assertThat(result.success).isTrue()
        // old: receivedLogs containsExactly("com.example.app").inOrder()
        assertThat(logs).containsExactly("com.example.app").inOrder()
        // old: driver.assertEvents([LaunchApp("com.example.app")])
        assertThat(provider.launchedApps).containsExactly("com.example.app")
    }

    @Test
    fun `Case 108 - fail the flow and skip commands in case of onStart hook failure`() {
        // onFlowStart: [evalScript logs "on start", assertTrue \${1-1}] -- assertTrue compiles to
        // AssertConditionCommand with a pure scriptCondition (no visible/notVisible clause), which
        // evaluateCondition resolves entirely in-engine (no device query at all -- see
        // FlowMatrixConditionalsTest Case 098b). 1-1 == 0, JS-falsy, so this throws a genuine
        // MaestroException.AssertionFailure -- the SAME exception type and the SAME cause the original
        // test asserted, no unwired-verb substitution needed. Because onFlowStart fails, the main
        // body's evalScript("main flow") never runs; onFlowComplete's evalScript("on complete") still
        // runs (finally block). Fully recovers the original's log order and exception type.
        val logs = mutableListOf<String>()
        assertThrows<MaestroException.AssertionFailure> {
            FlowMatrix.run("108_failed_start_hook", onLog = { logs.addAll(it) })
        }
        // old: receivedLogs containsExactly("on start", "on complete").inOrder()
        assertThat(logs).containsExactly("on start", "on complete").inOrder()
    }

    @Test
    fun `Case 109 - fail the flow and execute commands in case of onComplete hook failure`() {
        // onFlowStart: [evalScript logs "on start"] (succeeds); main body: [evalScript logs "main
        // flow"] (succeeds); onFlowComplete: [evalScript logs "on complete", assertTrue \${1-1}] --
        // same pure in-engine AssertionFailure as Case 108, this time in the onComplete hook. Fully
        // recovers the original's log order and exception type.
        val logs = mutableListOf<String>()
        assertThrows<MaestroException.AssertionFailure> {
            FlowMatrix.run("109_failed_complete_hook", onLog = { logs.addAll(it) })
        }
        // old: receivedLogs containsExactly("on start", "main flow", "on complete").inOrder()
        assertThat(logs).containsExactly("on start", "main flow", "on complete").inOrder()
    }
}
