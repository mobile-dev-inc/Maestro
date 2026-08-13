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
        // Decoupled from maestro.TreeNode: device-core has no serializable view tree, so the mapped
        // exception carries a null hierarchyRoot rather than an empty tree.
        assertThat((ex as MaestroException.ElementNotFound).hierarchyRoot).isNull()
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
        assertThat((ex as MaestroException.AssertionFailure).hierarchyRoot).isNull()
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
    fun `device core unavailable maps to device connection family`() {
        val mapped = DeviceCoreErrorMapper.mapInfraThrow(
            DeviceCoreUnavailable("no signal"), "assertVisible"
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
    fun `unrelated throwable is returned unchanged`() {
        val original = IllegalStateException("unrelated")
        val mapped = DeviceCoreErrorMapper.mapInfraThrow(original, "someOp")
        assertThat(mapped).isSameInstanceAs(original)
    }
}
