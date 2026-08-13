package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.AbsentVia
import dev.mobile.devicecore.prototype.api.InjectionUnavailable
import dev.mobile.devicecore.prototype.api.Outcome
import maestro.DeviceUnreachableException
import maestro.MaestroException
import maestro.device.Platform
import maestro.orchestra.ElementSelector
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeviceCoreDriverTest {

    private fun driver(provider: FakeDeviceProvider) =
        RealDeviceCoreDriver(providerFactory = { provider })

    @Test
    fun `launchApp records the app`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }
        val d = driver(provider)
        d.connect(DeviceCoreTarget(Platform.ANDROID), "com.example.example")
        d.launchApp("com.example.example")
        assertThat(provider.launchedApps).containsExactly("com.example.example")
    }

    @Test
    fun `launchApp failure surfaces UnableToLaunchApp`() {
        val provider = FakeDeviceProvider(launchFails = true) { DeviceCoreEvidence.absent("x") }
        val d = driver(provider)
        d.connect(DeviceCoreTarget(Platform.ANDROID), "com.example.example")
        // device-core's launchApp throws DeviceEnvError; the mapper turns it into UnableToLaunchApp.
        assertThrows<MaestroException.UnableToLaunchApp> { d.launchApp("com.example.example") }
    }

    @Test
    fun `assertVisibility VISIBLE passes when device-core reports visible`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.resolvedVisible("Form Test") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        d.assertVisibility(ElementSelector(textRegex = "Form Test"), AssertMode.VISIBLE)  // no throw
    }

    @Test
    fun `assertVisibility VISIBLE fails when absent`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("Form Test") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        assertThrows<MaestroException.AssertionFailure> {
            d.assertVisibility(ElementSelector(textRegex = "Form Test"), AssertMode.VISIBLE)
        }
    }

    @Test
    fun `assertVisibility NOT_VISIBLE passes when absent`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("kwyjibo") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        d.assertVisibility(ElementSelector(textRegex = "kwyjibo"), AssertMode.NOT_VISIBLE)  // no throw
    }

    @Test
    fun `assertVisibility NOT_VISIBLE fails when visible`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.resolvedVisible("Form Test") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        assertThrows<MaestroException.AssertionFailure> {
            d.assertVisibility(ElementSelector(textRegex = "Form Test"), AssertMode.NOT_VISIBLE)
        }
    }

    @Test
    fun `assertVisibility routes DeviceCoreUnavailable through the infra mapper`() {
        // Resolved, but device-core reports no visibility signal (an owed capability) — a real
        // production state. AssertVisibleVerdict throws DeviceCoreUnavailable, which must surface as
        // a DeviceUnreachableException (infra / trace-ERROR), never a raw DeviceCoreUnavailable and
        // never a silent pass.
        val provider = FakeDeviceProvider { DeviceCoreEvidence.resolvedUnavailableSignal("Form Test") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        assertThrows<DeviceUnreachableException> {
            d.assertVisibility(ElementSelector(textRegex = "Form Test"), AssertMode.VISIBLE)
        }
    }

    @Test
    fun `tap by id records the tap and returns the inject point`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        val chosen = d.tap(ElementSelector(idRegex = "fabAddIcon"))
        assertThat(provider.tapCount).isEqualTo(1)
        assertThat(chosen?.resourceId).isEqualTo("fabAddIcon")
        assertThat(chosen?.centerX).isEqualTo(10)
        assertThat(chosen?.centerY).isEqualTo(20)
    }

    @Test
    fun `tap surfaces a thrown infra failure as a device connection error`() {
        // A precondition-unmet throw from device-core during tap is infra, not a policy Outcome; the
        // mapper routes it to DeviceUnreachableException (a DeviceConnectionException), never a
        // MaestroException test-failure.
        val provider = FakeDeviceProvider(
            onTap = { throw InjectionUnavailable("gesture rejected") },
        ) { DeviceCoreEvidence.absent("x") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        assertThrows<DeviceUnreachableException> { d.tap(ElementSelector(idRegex = "fabAddIcon")) }
    }

    @Test
    fun `tap surfaces an Absent outcome as ElementNotFound`() {
        val provider = FakeDeviceProvider(
            tapOutcome = { Outcome.Absent(AbsentVia.CAP_WHILE_QUIET, capMs = 1000L) },
        ) { DeviceCoreEvidence.absent("x") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        assertThrows<MaestroException.ElementNotFound> {
            d.tap(ElementSelector(idRegex = "fabAddIcon"))
        }
    }
}
