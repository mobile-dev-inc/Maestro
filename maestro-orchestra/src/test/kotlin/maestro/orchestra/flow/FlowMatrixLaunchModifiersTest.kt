package maestro.orchestra.flow

import com.google.common.truth.Truth.assertThat
import maestro.MaestroException
import maestro.orchestra.devicecore.DeviceCoreEvidence
import maestro.orchestra.devicecore.FakeDeviceProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tier-2 coverage for the launchApp modifiers served by device-core (clearState, permissions),
 * wired per DEVICE_CORE_INTEGRATION.md "Implementing a new verb". The meaningful assertion is the
 * ORDER of device-core calls: `pm clear` resets runtime permissions, so a grant applied before the
 * clear would be silently lost — clearState must reach the device before setPermission, and both
 * before launchApp.
 */
class FlowMatrixLaunchModifiersTest {

    @Test
    fun `launchApp with clearState and permissions clears, grants, then launches - in that order`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent(it.toString()) }

        val result = FlowMatrix.run("launch_modifiers", provider)

        assertThat(result.success).isTrue()
        assertThat(provider.deviceCalls).containsExactly(
            "clearState:com.example.app",
            "setPermission:com.example.app:{all=allow}",
            "launchApp:com.example.app",
        ).inOrder()
    }

    @Test
    fun `a failed clearState fails the flow before launch, mapped through the error taxonomy`() {
        val provider = FakeDeviceProvider(clearStateFails = true) {
            DeviceCoreEvidence.absent(it.toString())
        }

        assertThrows<MaestroException> { FlowMatrix.run("launch_modifiers", provider) }

        assertThat(provider.launchedApps).isEmpty()
        assertThat(provider.grantedPermissions).isEmpty()
    }
}
