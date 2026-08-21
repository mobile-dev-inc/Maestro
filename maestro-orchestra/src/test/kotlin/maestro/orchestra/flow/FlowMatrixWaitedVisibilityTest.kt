package maestro.orchestra.flow

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.AbsentVia
import dev.mobile.devicecore.prototype.api.FoundVia
import dev.mobile.devicecore.prototype.api.Outcome
import maestro.MaestroException
import maestro.orchestra.devicecore.DeviceCoreEvidence
import maestro.orchestra.devicecore.FakeDeviceProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** assertVisible with an explicit `timeout:` and extendedWaitUntil both route to the waited verb;
 *  the verdict is read from the device-core Outcome the fake returns. */
class FlowMatrixWaitedVisibilityTest {

    @Test
    fun `assertVisible with timeout passes when waitFor reports Acted`() {
        val provider = FakeDeviceProvider(waitOutcome = { Outcome.Acted(FoundVia.AFTER_RENDER) }) {
            DeviceCoreEvidence.absent(it.toString())
        }
        val result = FlowMatrix.run("200_wait_visible", provider = provider)
        assertThat(result.success).isTrue()
    }

    @Test
    fun `assertVisible fails when waitFor never resolves`() {
        // A false verdict on a non-optional assertVisible propagates as an AssertionFailure from the
        // flow (evaluateCondition -> return false -> assertConditionCommand throws), not a swallowed
        // success = false.
        val provider = FakeDeviceProvider(waitOutcome = { Outcome.Absent(AbsentVia.CAP_WHILE_QUIET, capMs = 3000L) }) {
            DeviceCoreEvidence.absent(it.toString())
        }
        assertThrows<MaestroException.AssertionFailure> {
            FlowMatrix.run("200_wait_visible", provider = provider)
        }
    }

    @Test
    fun `extendedWaitUntil passes when waitFor reports Acted`() {
        val provider = FakeDeviceProvider(waitOutcome = { Outcome.Acted(FoundVia.AFTER_RENDER) }) {
            DeviceCoreEvidence.absent(it.toString())
        }
        val result = FlowMatrix.run("201_extended_wait_until", provider = provider)
        assertThat(result.success).isTrue()
    }
}
