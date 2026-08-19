package maestro.orchestra.flow

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.FoundVia
import dev.mobile.devicecore.prototype.api.Outcome
import dev.mobile.devicecore.prototype.api.Selector
import maestro.MaestroException
import maestro.orchestra.devicecore.DeviceCoreEvidence
import maestro.orchestra.devicecore.FakeDeviceProvider
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tier-2 recovery of the deleted `maestro-test` `IntegrationTest`'s repeat/retry family (cases 053,
 * 075, 082, 119, 120, 132, 141, plus the two standalone `retryCommand` unit-style cases — see git
 * history at c1538ee7^:maestro-test/src/test/kotlin/maestro/test/IntegrationTest.kt).
 *
 * Cases 132/141 exist to validate, end to end, the [maestro.orchestra.devicecore.DeviceCoreErrorMapper]
 * `CancellationException` rethrow that Task 3 added (already unit-tested directly in
 * `DeviceCoreErrorMapperTest`). [FlowMatrix.run] cannot drive that end-to-end: it wraps its own
 * `runBlocking { orchestra.runFlow(commands) }` internally with no way to pass in an outer
 * `CoroutineContext`/`Job`. Kotlin's `runBlocking` builds a brand-new ROOT job when given no context —
 * nesting it inside an outer `withTimeout { FlowMatrix.run(...) }` does NOT make the inner flow a child
 * of the outer job, so the outer timeout's cancellation can never reach the inner `repeat`/`retry`
 * loop. Cooperative cancellation is cooperative on a SHARED job tree; these are two disjoint trees.
 * Concretely: `withTimeout(2000) { FlowMatrix.run("...") }` would call `FlowMatrix.run` (an ordinary,
 * non-suspending function from the outer coroutine's point of view) which then blocks the SAME thread
 * inside its own nested `runBlocking`; the outer timeout firing only flips a flag on the OUTER job,
 * which is never observed because nothing in the inner flow is watching it. For 132's `repeat while:`
 * scenario (evidence held permanently absent so the loop never terminates on its own) this would hang
 * the test forever rather than surface `TimeoutCancellationException` — never flaky, always broken, so
 * both are recovered `@Disabled` per the task brief rather than as a hanging or silently-wrong test.
 */
class FlowMatrixRetryTest {

    @Test
    fun `Case 053 - Repeat N times`() {
        // 053_repeat_times.yaml: repeat(times: 3) { tapOn Button }, assertVisible "3"; evalScript sets
        // output.list = [1,2,3]; repeat(times: output.list.length = 3) { tapOn Button }, assertVisible
        // "6". repeat/tapOn/assertVisible are all wired. The old FakeDriver had a live element whose
        // text tracked an onClick-incremented counter; FakeDeviceProvider's evidenceFor is a pure
        // function of the selector with no device-side state to mutate, so the counter is recovered by
        // gating visibility of the literal digit text on the provider's own tapCount (closes over the
        // provider itself, safe since the lambda only runs after `provider` is assigned).
        lateinit var provider: FakeDeviceProvider
        provider = FakeDeviceProvider { sel ->
            val text = (sel as? Selector.Text)?.value
            if (text == provider.tapCount.toString()) DeviceCoreEvidence.resolvedVisible(sel.toString())
            else DeviceCoreEvidence.absent(sel.toString())
        }
        val result = FlowMatrix.run("053_repeat_times", provider = provider)
        assertThat(result.success).isTrue()
        // old: driver.assertEvents(6x Event.Tap(Point(50,50))) -- 3 from the first repeat, 3 from the
        // second (output.list.length == 3), each gated by a passing assertVisible on the running count.
        assertThat(provider.tapCount).isEqualTo(6)
    }

    @Test
    fun `Case 075 - Repeat while`() {
        // 075_repeat_while.yaml: repeat(while: notVisible "Value 3") { tapOn Button }, then assertVisible
        // "Value 3". `while: notVisible` is wired (evaluateCondition -> driver.assertVisibility). Same
        // stateless-provider adaptation as 053: "Value 3" becomes visible only once tapCount reaches 3,
        // reproducing the old FakeDriver's onClick-mutated counter view.
        lateinit var provider: FakeDeviceProvider
        provider = FakeDeviceProvider { sel ->
            val text = (sel as? Selector.Text)?.value
            if (text == "Value 3" && provider.tapCount >= 3) DeviceCoreEvidence.resolvedVisible(sel.toString())
            else DeviceCoreEvidence.absent(sel.toString())
        }
        val result = FlowMatrix.run("075_repeat_while", provider = provider)
        assertThat(result.success).isTrue()
        // old: driver.assertEventCount(Event.Tap(Point(50,50)), expectedCount = 3)
        assertThat(provider.tapCount).isEqualTo(3)
    }

    // 082's repeat body is [evalScript increment, inputText: ${output.value}, tapOn Button] -- inputText
    // is not yet a wired DeviceGateway verb, and it's the SECOND command in the body, reached on every
    // single iteration BEFORE tapOn: the very first pass through the loop throws NotImplemented before
    // any provider-observable state (no tap, no launch, no log -- evalScript is pure in-engine). An
    // assertThrows-only test can't discriminate "the while: true: JS condition and the increment loop
    // are correct" from any other broken wiring. Disabled instead of a false-green test.
    @Test
    @Disabled(
        "blocked on device-core inputText verb — repeat body's inputText throws before any tap runs on " +
            "the very first iteration, leaving no seam-observable state to assert the while:true: loop " +
            "count; see spec §5"
    )
    fun `Case 082 - Repeat while true`() {
        // TODO(inputText): original IntegrationTest (readCommands("082_repeat_while_true"), counter view
        // starting at "Value 0") ran evalScript(output.value=0), repeat(while: true: output.value<3) {
        // evalScript(output.value++), inputText(output.value), tapOn Button }, assertVisible "Value 3"
        // and asserted: driver.assertEventCount(Event.Tap(Point(50,50)), expectedCount = 3)
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("082_repeat_while_true")
        }
        assertThat(exception.message).contains("inputText")
    }

    // 119's retry body is [scroll, tapOn Button, scroll]. `scroll` (ScrollCommand -> driver.scrollVertical)
    // is NOT wired and is the FIRST command in the retry body -- it throws NotImplemented (itself a
    // MaestroException) on every single attempt, before tapOn ever runs, so the retry loop retries on
    // the WRONG kind of failure (an unrelated unwired verb, not the tap flake the original case is
    // actually about) and tapCount never leaves 0 no matter how many attempts run. No seam-observable
    // state distinguishes "retried the right number of times because of a tap flake" from "retried
    // because scroll is unimplemented". Disabled instead of a test that verifies the wrong mechanism.
    @Test
    @Disabled(
        "blocked on device-core scroll verb — the retry body's leading `scroll` throws NotImplemented " +
            "on every attempt before tapOn ever runs, so retry only ever exercises the unrelated unwired " +
            "verb, not the tap-flake retry trigger the original case tests; see spec §5. The general " +
            "retry-on-MaestroException mechanism itself IS covered end to end below by " +
            "`retryCommand retries on MaestroException until success` / `... propagates other " +
            "throwables without retrying`, using a tap-only fixture."
    )
    fun `Case 119 - Retry set of commands with n attempts`() {
        // TODO(scroll): original IntegrationTest (readCommands("119_retry_commands")) had the button's
        // onClick throw MaestroException.UnableToLaunchApp on the first attempt only, then succeed, and
        // asserted the retry-wrapped [scroll, tapOn, scroll] ran twice (fail once, succeed once):
        //   driver.assertEvents([Scroll, TakeScreenshot, /*retry*/ Scroll, TakeScreenshot, Tap(50,50), Scroll])
        val exception = assertThrows<MaestroException.NotImplemented> {
            FlowMatrix.run("119_retry_commands")
        }
        assertThat(exception.message).contains("scrollVertical")
    }

    @Test
    fun `Case 120 - Tap on element - Retry if no UI change opt-in`() {
        // 120_tap_on_element_retryTapIfNoChange.yaml: a single `tapOn: { text: ..., retryTapIfNoChange:
        // true }`. TapOnElementCommand IS wired (driver.tap), but Orchestra.tapOnElement takes a
        // `retryIfNoChange` parameter and never reads it (Orchestra.kt:1308-1334) -- device-core owns
        // element resolution and there is no Maestro-side hierarchy diff to gate a same-element retap
        // on. So this now taps exactly ONCE, not twice: a genuine, intentional behavior divergence
        // (the flag is accepted, parsed, and silently a no-op under this gateway -- not a NotImplemented
        // throw), not an unwired-verb substitution. Recovered faithfully to what actually happens now;
        // old expectation was tapCount == 2 (legacy hierarchy-diff retry fired once).
        val provider = FakeDeviceProvider { DeviceCoreEvidence.resolvedVisible(it.toString()) }
        val result = FlowMatrix.run("120_tap_on_element_retryTapIfNoChange", provider = provider)
        assertThat(result.success).isTrue()
        // old: driver.assertEventCount(Event.Tap(Point(50,50)), expectedCount = 2)
        assertThat(provider.tapCount).isEqualTo(1)
    }

    // See class doc: an outer withTimeout/cancellation can never reach a flow run through
    // FlowMatrix.run's own nested root-level runBlocking -- two disjoint job trees, not cooperative
    // cancellation across a shared one. 132's scenario (evidence held permanently absent so `repeat
    // while:` never terminates on its own) would hang the test forever rather than surface
    // TimeoutCancellationException if attempted through this harness. Disabled rather than a hanging test.
    @Test
    @Disabled(
        "FlowMatrix.run wraps its own root-level runBlocking with no injectable outer CoroutineContext/" +
            "Job, so an enclosing withTimeout can never cooperatively cancel the inner repeat loop -- " +
            "attempting this would hang the test (permanently-absent evidence keeps `while:` true " +
            "forever) rather than exercise cancellation. See class doc. Task 3's CancellationException " +
            "rethrow is unit-tested directly in DeviceCoreErrorMapperTest; a true end-to-end proof needs " +
            "a FlowMatrix variant that accepts/propagates an outer CoroutineScope. TODO: once available, " +
            "assert withTimeout(2000) { FlowMatrix.run(\"132_repeat_while_timeout\", ...) } throws " +
            "TimeoutCancellationException with tapCount > 0 (some commands ran before cancellation)."
    )
    fun `Case 132 - repeatWhile respects coroutine timeout and gets cancelled`() {
        // TODO: see @Disabled reason above.
    }

    // Same structural block as 132 (see class doc), plus the original's retry body is
    // scrollUntilVisible (never wired -- NotImplemented, itself a MaestroException) with a bounded
    // maxRetries=3: under this gateway that throws quickly after a few retries rather than looping
    // toward a 60s per-attempt timeout, so even ignoring the cancellation-propagation problem the
    // original scenario (retry exhausts wall-clock time via a long per-attempt operation) no longer
    // exists. Disabled rather than a test that can't reach the scenario it's meant to prove.
    @Test
    @Disabled(
        "FlowMatrix.run cannot propagate an outer withTimeout's cancellation into the inner flow (see " +
            "class doc / Case 132), and the original's retry body (scrollUntilVisible looping toward a " +
            "60s per-attempt timeout) has no wired equivalent to reproduce a long-running attempt for " +
            "cancellation to interrupt. Task 3's CancellationException rethrow is unit-tested directly " +
            "in DeviceCoreErrorMapperTest. TODO: once FlowMatrix supports an injectable outer scope and " +
            "device-core wires a long-running verb, assert withTimeout(2000) { FlowMatrix.run(retry-" +
            "wrapping-a-slow-verb) } throws TimeoutCancellationException."
    )
    fun `Case 141 - retryCommand respects coroutine cancellation`() {
        // TODO: see @Disabled reason above.
    }

    @Test
    fun `retryCommand only retries on MaestroException, propagates other throwables without retrying`() {
        // New standalone fixture (retry_tap_only.yaml: retry(maxRetries: 3) { tapOn Button }) rather
        // than 119_retry_commands.yaml, whose leading `scroll` is unwired and would block tapOn from
        // ever being reached (see Case 119 above). Original built this same [retry -> tapOnElement]
        // shape programmatically rather than via a fixture; this is the FlowMatrix-idiomatic equivalent.
        // onTap throws a plain RuntimeException -- not any device-core type mapInfraThrow recognizes, so
        // it passes through unmapped and is not a MaestroException -- retryCommand's `catch (exception:
        // MaestroException)` never engages.
        var tapCount = 0
        val provider = FakeDeviceProvider(
            onTap = { tapCount++; throw RuntimeException("infra failure — not a MaestroException") },
        ) { DeviceCoreEvidence.absent(it.toString()) }
        val thrown = assertThrows<RuntimeException> {
            FlowMatrix.run("retry_tap_only", provider = provider)
        }
        // old: thrown.message == "infra failure — not a MaestroException"; thrown !is MaestroException
        assertThat(thrown.message).isEqualTo("infra failure — not a MaestroException")
        assertThat(thrown).isNotInstanceOf(MaestroException::class.java)
        // old: tapCount == 1 -- the inner tap ran exactly once, no retries were attempted
        assertThat(tapCount).isEqualTo(1)
        assertThat(provider.tapCount).isEqualTo(1)
    }

    @Test
    fun `retryCommand retries on MaestroException until success`() {
        // Same retry_tap_only.yaml fixture. The retriable lever is `tapOutcome` returning
        // Outcome.Absent on the first tap -- DeviceCoreErrorMapper.tapOutcomeToException maps that to
        // MaestroException.ElementNotFound, a genuine MaestroException, so retryCommand's catch clause
        // replays the subflow. (An onTap infra-throw of a device-core type like InjectionUnavailable
        // does NOT work here: DeviceCoreErrorMapper.mapInfraThrow maps it to DeviceUnreachableException,
        // which is explicitly NOT a MaestroException -- see DeviceUnreachableException's own doc -- so
        // it would propagate immediately without retrying. Confirmed by running that lever first: it
        // fails with the wrong exception type / attempts stuck at 1.)
        var attempts = 0
        val provider = FakeDeviceProvider(
            onTap = { attempts++ },
            tapOutcome = { if (attempts == 1) Outcome.Absent(dev.mobile.devicecore.prototype.api.AbsentVia.CAP_WHILE_QUIET, capMs = 0) else Outcome.Acted(FoundVia.IMMEDIATE) },
        ) { DeviceCoreEvidence.absent(it.toString()) }
        val result = FlowMatrix.run("retry_tap_only", provider = provider)
        assertThat(result.success).isTrue()
        assertThat(attempts).isAtLeast(2)
        // old: tapCount == 2 -- fails once (flake), succeeds on retry
        assertThat(provider.tapCount).isEqualTo(2)
    }
}
