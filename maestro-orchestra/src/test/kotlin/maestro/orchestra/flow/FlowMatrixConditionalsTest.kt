package maestro.orchestra.flow

import com.google.common.truth.Truth.assertThat
import maestro.MaestroException
import maestro.orchestra.devicecore.DeviceCoreEvidence
import maestro.orchestra.devicecore.FakeDeviceProvider
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tier-2 recovery of the deleted `maestro-test` `IntegrationTest`'s conditionals family (cases
 * 049, 065, 096, 098a, 098b — see git history at c1538ee7^:maestro-test/src/test/kotlin/maestro/test/IntegrationTest.kt).
 * Arrange changes from seeding a `FakeDriver` screen (with stateful `onClick` element mutation) to
 * seeding evidence/outcomes at the [FakeDeviceProvider] seam; assertions port from the old driver's
 * recorded `Event` stream to the equivalent provider state.
 */
class FlowMatrixConditionalsTest {

    @Test
    fun `Case 049 - Run flow conditionally`() {
        // Old arrange seeded a screen whose onClick mutated a sibling ("Not Clicked" -> "Clicked") to
        // gate the second condition dynamically. The new provider seam resolves evidence per-selector
        // with no device-side state to mutate, so the "click changed the indicator" temporal behavior
        // is simulated with a query-count gate on the "Not Clicked" selector: visible on its first
        // lookup (flow's first runFlow guard, before the tap), absent from then on (flow's third
        // runFlow guard, re-checking the same env-supplied text after the tap) -- reproducing the
        // original's "guarded subflow runs exactly once" outcome. "Clicked" (the middle assertVisible)
        // is unrelated to that gate and always resolves visible.
        // This gate assumes Orchestra issues exactly one inspect() call per `runFlow` visibility
        // guard (true here because FlowMatrix sets lookupTimeoutMs=0 / optionalLookupTimeoutMs=0,
        // so there are no lookup retries). See Orchestra.evaluateCondition -- if that single-inspect-
        // per-guard contract ever changes, this gate's query-count semantics need to be revisited.
        var notClickedQueries = 0
        val provider = FakeDeviceProvider { sel ->
            val key = sel.toString()
            if (key.contains("Not Clicked")) {
                notClickedQueries++
                if (notClickedQueries == 1) DeviceCoreEvidence.resolvedVisible(key) else DeviceCoreEvidence.absent(key)
            } else {
                DeviceCoreEvidence.resolvedVisible(key)
            }
        }
        val result = FlowMatrix.run(
            "049_run_flow_conditionally",
            provider = provider,
            env = mapOf("NOT_CLICKED" to "Not Clicked"),
        )
        assertThat(result.success).isTrue()
        assertThat(provider.tapCount).isEqualTo(1)  // old: assertEventCount(Event.Tap(Point(50,50)), 1)
    }

    // Fix round 1: the guard is `runFlow: when: visible: ...` gating a subflow whose only command is
    // `inputText`, which is not yet a wired DeviceGateway verb. That guard is the FIRST command in the
    // flow, so there is no pre-throw provider state (no launch/tap/assert) to assert -- an
    // `assertThrows`-only test would pass whether or not the "when: true: <JS truthy value>" branch
    // evaluation this case exists to verify is actually correct. Disabled instead of a false-green test.
    @Test
    @Disabled(
        "blocked on device-core inputText verb — no seam-observable state to assert JS-truthy " +
            "`when: true:` branch selection until inputText is wired; see spec §5"
    )
    fun `Case 065 - When True condition`() {
        // TODO(inputText): original IntegrationTest (readCommands("065_when_true"), empty screen) ran
        // ten runFlow branches gated on `when: true: <value>` (065_subflow.yaml does `inputText: ${name}`)
        // and asserted only the JS-truthy branches fired, in order:
        //   driver.assertEvents(listOf(
        //       Event.InputText("True"),          // true: ${true}
        //       Event.InputText("String"),         // true: ${'String'}
        //       Event.InputText("Positive Int"),   // true: ${123}
        //       Event.InputText("Object"),         // true: ${{field: 'value'}}
        //       Event.InputText("Array"),          // true: ${[]}
        //   ))
        // False/falsy branches (false, undefined, empty, null, zero) must NOT produce an InputText.
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("065_when_true")
        }
        assertThat(exception.message).contains("inputText")
    }

    // Fix round 1: `when: platform:` resolves purely in-Orchestra against the harness's hardcoded
    // Platform.ANDROID (FlowMatrix has no platform parameter) -- so, unlike the original run under
    // iOS, only the THIRD runFlow (platform: Android) matches here; the first two (iOS, ios) are
    // in-engine skips with no provider-observable trace either way. The Android branch's only command
    // is `inputText`, still unwired, and it's the first command reached in that branch -- no
    // pre-throw provider state -- so an assertThrows-only test can't discriminate "the right branch
    // matched" from "the wrong branch matched" (both produce the identical NotImplemented("inputText")
    // exception with zero observable state). Disabled rather than a false-green test.
    @Test
    @Disabled(
        "blocked on device-core inputText verb — no seam-observable state to distinguish which " +
            "`when: platform:` branch matched until inputText is wired; see spec §5"
    )
    fun `Case 096 - platform condition`() {
        // TODO(inputText): original IntegrationTest (readCommands("096_platform_condition"), run under
        // iOS) asserted:
        //   driver.assertHasEvent(Event.InputText("Hello iOS"))     // platform: iOS matched
        //   driver.assertHasEvent(Event.InputText("Hello ios"))     // platform: ios matched (case-insensitive)
        //   driver.assertNoEvent(Event.InputText("Hello Android"))  // platform: Android did not match
        // Under this harness's hardcoded Platform.ANDROID, the expected outcome flips: "Hello Android"
        // should fire and "Hello iOS"/"Hello ios" should not.
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("096_platform_condition")
        }
        assertThat(exception.message).contains("inputText")
    }

    @Test
    @Disabled("waitFor(GONE) — device-core roadmap: assertNotVisible throws NotImplemented; see spec §5")
    fun `Case 098a - Execute Javascript conditionally`() {
        // TODO(waitFor GONE): when device-core ships waitFor(GONE), restore the original success
        // assertion: result.success == true; provider.tapCount == 1; logs == ["Log from runScript"].
        // The flow's `assertNotVisible: Click me` step now throws NotImplemented, truncating the flow
        // before the second runScript (gated on `visible: Not Clicked`) — the case's actual point.
        // Old arrange seeded a "Click me" element whose onClick mutated its own text to "Clicked"; new
        // arrange tracks the same before/after-tap transition via the provider's onTap hook, since
        // evidenceFor is otherwise a stateless function of the selector alone. Every verb this fixture
        // touches (runScript's own JS execution, tapOn, assertVisible/assertNotVisible) is wired, so
        // this recovers as a full success assertion, as originally written.
        var clicked = false
        val logs = mutableListOf<String>()
        val provider = FakeDeviceProvider(
            onTap = { clicked = true },
            evidenceFor = { sel ->
                val key = sel.toString()
                // "Not Clicked" (the second runScript's guard) must stay absent throughout -- checked
                // first since it's a substring superset of "Clicked" and would otherwise fall into
                // that branch once `clicked` flips true.
                val visible = when {
                    key.contains("Not Clicked") -> false
                    key.contains("Click me") -> !clicked
                    key.contains("Clicked") -> clicked
                    else -> false
                }
                if (visible) DeviceCoreEvidence.resolvedVisible(key) else DeviceCoreEvidence.absent(key)
            },
        )
        val result = FlowMatrix.run("098_runscript_conditionals", provider = provider, onLog = { logs.addAll(it) })
        assertThat(result.success).isTrue()
        // old: driver.assertEventCount(Event.Tap(Point(50,50)), 1)
        assertThat(provider.tapCount).isEqualTo(1)
        // old: assertThat(receivedLogs).containsExactly("Log from runScript").inOrder() -- the second
        // runScript is gated on `visible: Not Clicked` (absent under this provider), so it never runs.
        assertThat(logs).containsExactly("Log from runScript").inOrder()
    }

    @Test
    fun `Case 098b - Execute conditions eagerly`() {
        // `when: true: ${ 1 + 1 != 2 }` evaluates to the JS-false "false" literal; evaluateCondition's
        // scriptCondition check short-circuits (a non-local `return false`) before ever reaching the
        // `visible: Click me` sub-condition, so no device query happens at all -- fully recovers the
        // original's "no events, script did not run, flow still completes" shape with no unwired verb
        // in the way.
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent(it.toString()) }
        val logs = mutableListOf<String>()
        val result = FlowMatrix.run(
            "098_runscript_conditionals_eager",
            provider = provider,
            onLog = { logs.addAll(it) },
        )
        assertThat(result.success).isTrue()
        assertThat(provider.tapCount).isEqualTo(0)
        assertThat(provider.lastInspectedSelector).isNull()
        assertThat(logs).isEmpty()
    }
}
