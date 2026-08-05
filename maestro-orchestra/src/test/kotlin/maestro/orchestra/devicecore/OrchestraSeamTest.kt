package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.Maestro
import maestro.device.Platform
import maestro.orchestra.*
import org.junit.jupiter.api.Test

/**
 * Proves the Orchestra seam: a standalone assertVisible/assertNotVisible is routed to
 * device-core via [DeviceCoreAssertRouter], while a `when:`/`while:` guard (evaluated through
 * legacy `evaluateCondition`) never touches device-core. `fake.lastSelector` is the tell —
 * it's only ever set inside `FakeDeviceProvider`'s `inspect()`.
 */
class OrchestraSeamTest {
    private val ua = Signal(false, EvidenceSource.UNAVAILABLE)
    private fun resolvedOnScreen() = ElementEvidence(
        "t", Resolution.Resolved(ResolvedChannel.TEXT),
        Actionability(ua, ua, Signal(true, EvidenceSource.MEASURED), ua, ua),
        Sourced(Rect(122, 160, 148, 26), EvidenceSource.MEASURED),
    )

    private fun maestroStub(): Maestro = mockk(relaxed = true) {
        every { cachedDeviceInfo } returns DeviceInfo(
            platform = Platform.IOS,
            widthPixels = 1179,
            heightPixels = 2556,
            widthGrid = 393,
            heightGrid = 852,
        )
    }

    @Test
    fun `standalone assertVisible is routed to device-core`() {
        val fake = FakeDeviceProvider { resolvedOnScreen() }
        val orchestra = Orchestra(
            maestro = maestroStub(),
            deviceCoreAssertRouter = DeviceCoreAssertRouter("com.x") { fake },
        )
        val cmd = MaestroCommand(
            assertConditionCommand = AssertConditionCommand(
                condition = Condition(visible = ElementSelector(textRegex = "Welcome")),
            ),
        )

        val result = runBlocking { orchestra.runFlow(listOf(cmd)) }

        assertThat(result.success).isTrue()
        // device-core consulted
        assertThat(fake.lastSelector).isEqualTo(Selector.Text("Welcome", Match.EXACT))
    }

    @Test
    fun `runFlow when-guard is NOT routed (stays on legacy)`() {
        val fake = FakeDeviceProvider { resolvedOnScreen() }
        val orchestra = Orchestra(
            maestro = maestroStub(),
            deviceCoreAssertRouter = DeviceCoreAssertRouter("com.x") { fake },
            // Legacy evaluateCondition polls maestro.viewHierarchy() until this timeout elapses
            // when the (mocked) hierarchy never contains a match. Keep it tiny so the negative
            // control (device-core never consulted) doesn't cost the default 7s per run.
            lookupTimeoutMs = 50L,
            optionalLookupTimeoutMs = 50L,
        )
        val runFlow = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = emptyList(),
                condition = Condition(visible = ElementSelector(textRegex = "Welcome")),
                config = null,
            ),
        )

        runBlocking { orchestra.runFlow(listOf(runFlow)) }

        // when: guard used legacy evaluateCondition, never device-core
        assertThat(fake.lastSelector).isNull()
    }
}
