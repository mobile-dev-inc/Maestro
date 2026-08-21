package maestro.orchestra.flow

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.AbsentVia
import dev.mobile.devicecore.prototype.api.FoundVia
import dev.mobile.devicecore.prototype.api.Outcome
import dev.mobile.devicecore.prototype.api.Selector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * `CancellationException` rethrow that Task 3 added (already unit-tested directly and
 * deterministically in `DeviceCoreErrorMapperTest`). [FlowMatrix.run] cannot drive that end-to-end on
 * its own: it wraps `orchestra.runFlow(commands)` in its own `runBlocking { ... }`, and Kotlin's
 * `runBlocking` builds a brand-new ROOT job when given no context — nesting it inside an outer
 * `withTimeout { FlowMatrix.run(...) }` does NOT make the inner flow a child of the outer job, so the
 * outer timeout's cancellation can never reach the inner `repeat`/`retry` loop (cooperative
 * cancellation only works across a SHARED job tree). [FlowMatrix.runSuspend] (fix round 1) is the
 * `run` internals MINUS that `runBlocking` wrapper, letting a test call it directly from inside its
 * OWN `withTimeout` block so the flow runs in the SAME job tree as the timeout — Cases 132 and 141
 * below both use it to drive a real, deterministic end-to-end cancellation (132 via `repeatCommand`'s
 * own `yield()`; 141 via the added [FakeDeviceProvider.delayMs] lever, see its own doc for why that
 * was needed and why it's still deterministic despite the delay itself being uninterruptible).
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
    @Disabled("waitFor(GONE) — device-core roadmap: while:notVisible throws NotImplemented; see spec §5")
    fun `Case 075 - Repeat while`() {
        // TODO(waitFor GONE): when device-core ships waitFor(GONE), restore: provider.tapCount == 3.
        // `repeat while: notVisible "Value 3"` throws NotImplemented on the first guard evaluation, so
        // zero taps run.
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

    // Fix round 1: `retryIfNoChange` retap behavior LEFT Maestro for device-core (spec §5 --
    // device-behavior we no longer own): Orchestra.tapOnElement takes a `retryIfNoChange` parameter
    // and never reads it (Orchestra.kt:1308-1334); the flag is parsed but inert. A `tapCount == 1`
    // assertion documents nothing unique -- it's indistinguishable from any other single-tap test, so
    // it doesn't actually recover anything about this case. Disabled rather than an assertion that
    // looks like a faithful port but isn't.
    @Test
    @Disabled(
        "retryIfNoChange retap behavior moved to device-core and is not served at the seam; the flag " +
            "is parsed but inert — nothing Maestro-owned to assert here; see spec §5"
    )
    fun `Case 120 - Tap on element - Retry if no UI change opt-in`() {
        // TODO(retryIfNoChange): original IntegrationTest
        // (readCommands("120_tap_on_element_retryTapIfNoChange")) asserted
        // driver.assertEventCount(Event.Tap(Point(50,50)), expectedCount = 2) -- the legacy
        // hierarchy-diff retap fired once because the tapped element's bounds never changed between
        // the first tap and the recheck. Reactivate with that same assertion if device-core ever
        // serves an equivalent no-visible-change retry signal at the seam.
        val provider = FakeDeviceProvider { DeviceCoreEvidence.resolvedVisible(it.toString()) }
        val result = FlowMatrix.run("120_tap_on_element_retryTapIfNoChange", provider = provider)
        assertThat(result.success).isTrue()
        assertThat(provider.tapCount).isEqualTo(2)
    }

    @Test
    fun `Case 132 - repeatWhile respects coroutine timeout and gets cancelled`() {
        // Fix round 1: now driven as a REAL end-to-end cancellation test via FlowMatrix.runSuspend
        // (not run) -- runSuspend has no runBlocking wrapper of its own, so it runs in the SAME
        // coroutine/Job the outer withTimeout below establishes; run()'s nested runBlocking would
        // instead build a disjoint root job that never observes the outer cancellation (see FlowMatrix
        // .kt and Task 10 report for why).
        //
        // 132_repeat_while_timeout.yaml: runFlow { repeat(while: visible "Value 0") { tapOn Button } }.
        // "Value 0" is held permanently visible, so the while: condition never goes false on its own --
        // the loop only ends when the outer withTimeout(2000)'s cancellation reaches repeatCommand's
        // yield() checkpoint (Orchestra.kt:874-875), thrown as a genuine coroutine
        // TimeoutCancellationException, NOT a mapped device verdict -- the end-to-end proof of Task 3's
        // CancellationException rethrow through the whole runFlow stack.
        var tapCount = 0
        val provider = FakeDeviceProvider(onTap = { tapCount++ }) { sel ->
            val text = (sel as? Selector.Text)?.value
            if (text == "Value 0") DeviceCoreEvidence.resolvedVisible(sel.toString())
            else DeviceCoreEvidence.absent(sel.toString())
        }
        val thrown = assertThrows<TimeoutCancellationException> {
            runBlocking {
                withTimeout(2000) {
                    FlowMatrix.runSuspend("132_repeat_while_timeout", provider = provider)
                }
            }
        }
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        // old: assertThat(completed).isGreaterThan(0) -- some commands ran before cancellation
        assertThat(tapCount).isGreaterThan(0)
    }

    @Test
    fun `Case 141 - retryCommand respects coroutine cancellation`() {
        // Fix round 1: retryCommand's own loop (Orchestra.kt:897-922) has NO yield()/suspension point
        // of its own -- it only suspends by calling into runSubFlow, whose executeSubflowCommands calls
        // yield() once per command at the top of each attempt (Orchestra.kt:1079). With
        // FakeDeviceProvider's tap()/inspect() synchronous, a bounded retry (MAX_RETRIES_ALLOWED = 3,
        // so 4 attempts total) completes in well under a millisecond -- nowhere near long enough for a
        // 2s withTimeout to ever land. The new [FakeDeviceProvider.delayMs] lever gives each attempt
        // real wall-clock duration (700ms) to close that gap: it does NOT make an in-flight tap
        // interruptible (the delay runs inside RealDeviceGateway's own disjoint nested runBlocking, see
        // delayMs's doc) but it doesn't need to -- the delay always completes, and CUMULATIVE elapsed
        // time across attempts is what matters: 700ms * 4 attempts = 2800ms > the 2000ms timeout, so the
        // NEXT attempt's yield() (a genuine shared-tree suspension point) always observes the
        // already-fired outer cancellation before the loop can exhaust all 4 attempts and return/throw
        // its own (non-cancellation) verdict. tapOutcome always Absent -> every attempt fails and
        // retries, giving the loop every chance to keep running until cancelled.
        var tapCount = 0
        val provider = FakeDeviceProvider(
            onTap = { tapCount++ },
            tapOutcome = { Outcome.Absent(AbsentVia.CAP_WHILE_QUIET, capMs = 0) },
            delayMs = 700,
        ) { DeviceCoreEvidence.absent(it.toString()) }
        val thrown = assertThrows<TimeoutCancellationException> {
            runBlocking {
                withTimeout(2000) {
                    FlowMatrix.runSuspend("retry_tap_only", provider = provider)
                }
            }
        }
        assertThat(thrown).isInstanceOf(CancellationException::class.java)
        // old: assertThat(startedCommands).isGreaterThan(0) -- retry was attempted before cancellation
        assertThat(tapCount).isGreaterThan(0)
        // Never exhausts MAX_RETRIES_ALLOWED (4 attempts) -- cancellation always lands first.
        assertThat(tapCount).isLessThan(4)
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
            tapOutcome = { if (attempts == 1) Outcome.Absent(AbsentVia.CAP_WHILE_QUIET, capMs = 0) else Outcome.Acted(FoundVia.IMMEDIATE) },
        ) { DeviceCoreEvidence.absent(it.toString()) }
        val result = FlowMatrix.run("retry_tap_only", provider = provider)
        assertThat(result.success).isTrue()
        assertThat(attempts).isAtLeast(2)
        // old: tapCount == 2 -- fails once (flake), succeeds on retry
        assertThat(provider.tapCount).isEqualTo(2)
    }
}
