package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.AbsentVia
import dev.mobile.devicecore.prototype.api.DeviceResolutionFailure
import dev.mobile.devicecore.prototype.api.FoundVia
import dev.mobile.devicecore.prototype.api.InjectionUnavailable
import dev.mobile.devicecore.prototype.api.Outcome
import maestro.DeviceConnectionException
import maestro.MaestroException
import org.junit.jupiter.api.Test

class DeviceCoreErrorMapperTest {
    @Test
    fun `absent tap outcome maps to ElementNotFound with no hierarchy`() {
        val ex = DeviceCoreErrorMapper.tapOutcomeToException(
            Outcome.Absent(AbsentVia.CAP_WHILE_QUIET, capMs = 0), "id=login"
        )
        assertThat(ex).isInstanceOf(MaestroException.ElementNotFound::class.java)
    }

    @Test
    fun `acted tap outcome maps to null`() {
        val ex = DeviceCoreErrorMapper.tapOutcomeToException(Outcome.Acted(FoundVia.IMMEDIATE), "id=login")
        assertThat(ex).isNull()
    }

    @Test
    fun `crashed tap outcome maps to AppCrash`() {
        val ex = DeviceCoreErrorMapper.tapOutcomeToException(Outcome.Crashed("com.example.example"), "id=login")
        assertThat(ex).isInstanceOf(MaestroException.AppCrash::class.java)
    }

    @Test
    fun `blocked tap outcome maps to AssertionFailure with no hierarchy`() {
        val ex = DeviceCoreErrorMapper.tapOutcomeToException(
            Outcome.Blocked(detail = "not enabled"), "id=login"
        )
        assertThat(ex).isInstanceOf(MaestroException.AssertionFailure::class.java)
    }

    @Test
    fun `device resolution failure maps to device connection family`() {
        val mapped = DeviceCoreErrorMapper.mapInfraThrow(
            DeviceResolutionFailure.NoDevice(), "connect"
        )
        assertThat(mapped).isInstanceOf(DeviceConnectionException::class.java)
    }

    @Test
    fun `injection unavailable maps to device connection family`() {
        val mapped = DeviceCoreErrorMapper.mapInfraThrow(
            InjectionUnavailable("target not prepared"), "tap"
        )
        assertThat(mapped).isInstanceOf(DeviceConnectionException::class.java)
    }

    @Test
    fun `device env error maps to UnableToLaunchApp`() {
        val cause = RuntimeException("boom")
        val envError = dev.mobile.devicecore.prototype.api.DeviceEnvError.TransportFailure("launchApp", cause)
        val mapped = DeviceCoreErrorMapper.mapInfraThrow(envError, "launchApp")
        assertThat(mapped).isInstanceOf(MaestroException.UnableToLaunchApp::class.java)
    }

    @Test
    fun `NotImplementedError maps to NotImplemented`() {
        val mapped = DeviceCoreErrorMapper.mapInfraThrow(
            NotImplementedError("waitFor: no iOS strategy registered (ROADMAP: waitFor)"), "assert id=login"
        )
        assertThat(mapped).isInstanceOf(MaestroException.NotImplemented::class.java)
    }

    @Test
    fun `unrelated throwable is returned unchanged`() {
        val original = IllegalStateException("unrelated")
        val mapped = DeviceCoreErrorMapper.mapInfraThrow(original, "someOp")
        assertThat(mapped).isSameInstanceAs(original)
    }

    @Test
    fun `cancellation is rethrown unmapped, never laundered into a device exception`() {
        val cancel = kotlinx.coroutines.CancellationException("flow cancelled")
        val thrown = org.junit.jupiter.api.assertThrows<kotlinx.coroutines.CancellationException> {
            DeviceCoreErrorMapper.mapInfraThrow(cancel, "tap")
        }
        assertThat(thrown).isSameInstanceAs(cancel)
    }
}
