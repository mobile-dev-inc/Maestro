package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.AbsentVia
import dev.mobile.devicecore.prototype.api.InjectionUnavailable
import dev.mobile.devicecore.prototype.api.Outcome
import dev.mobile.devicecore.prototype.api.Travel
import maestro.DeviceUnreachableException
import maestro.KeyCode
import maestro.MaestroException
import maestro.Point
import maestro.SwipeDirection
import maestro.device.DeviceOrientation
import maestro.device.Platform
import maestro.orchestra.ElementSelector
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DeviceGatewayTest {

    private fun driver(provider: FakeDeviceProvider) =
        RealDeviceGateway(providerFactory = { provider })

    @Test
    fun `connect threads the resolved device serial into the device-core target selector`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }
        val d = driver(provider)
        d.connect(DeviceCoreTarget(Platform.ANDROID, "emulator-5554"), "com.example.example")
        assertThat(provider.lastConnectedTarget?.serial).isEqualTo("emulator-5554")
    }

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
        d.assertVisibility(ElementSelector(textRegex = "Form Test"), AssertMode.VISIBLE, timeoutMs = 1000L)  // no throw
    }

    @Test
    fun `assertVisibility VISIBLE fails with AssertionFailure when absent`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("Form Test") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        assertThrows<MaestroException.AssertionFailure> {
            d.assertVisibility(ElementSelector(textRegex = "Form Test"), AssertMode.VISIBLE, timeoutMs = 1000L)
        }
    }

    @Test
    fun `assertVisibility NOT_VISIBLE throws NotImplemented (no device-core GONE verb)`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("kwyjibo") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        assertThrows<MaestroException.NotImplemented> {
            d.assertVisibility(ElementSelector(textRegex = "kwyjibo"), AssertMode.NOT_VISIBLE, timeoutMs = 1000L)
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

    // --- Roadmap verbs: grown onto the interface with no behavior yet. Every one of these throws
    // MaestroException.NotImplemented from RealDeviceGateway until a later task wires it to
    // device-core. Nothing calls them yet (Orchestra still calls Maestro directly) — these tests
    // exist purely to pin the seam's shape and the throwing contract.

    private fun unimplementedDriver() = driver(FakeDeviceProvider { DeviceCoreEvidence.absent("x") })

    @Test
    fun `takeScreenshot is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.takeScreenshot(Buffer(), false) }
    }

    @Test
    fun `takeScreenshot cropped to a selector is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> {
            d.takeScreenshot(Buffer(), false, ElementSelector(idRegex = "root"))
        }
    }

    @Test
    fun `startScreenRecording is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.startScreenRecording(Buffer()) }
    }

    @Test
    fun `hierarchy is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.hierarchy() }
    }

    @Test
    fun `inputText writes to the focused node via device-core`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        d.inputText("hello")   // no throw; the seam hands device-core a sentinel locator + the text
        assertThat(provider.lastInputText).isEqualTo("hello")
    }

    @Test
    fun `eraseText is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.eraseText(3) }
    }

    @Test
    fun `pressKey routes a mapped KeyCode to device-core Screen pressKey`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        d.pressKey(KeyCode.HOME)   // HOME maps to device-core Key.HOME
        assertThat(provider.lastPressedKey).isEqualTo(dev.mobile.devicecore.prototype.api.Key.HOME)
    }

    @Test
    fun `pressKey walls a KeyCode device-core's Key enum does not cover`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        // POWER is not in device-core's Key enum -> honest wall, never aliased onto a near key.
        assertThrows<MaestroException.NotImplemented> { d.pressKey(KeyCode.POWER) }
    }

    @Test
    fun `backPress routes to device-core Screen back`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        d.backPress()   // no throw
        assertThat(provider.backCount).isEqualTo(1)
    }

    @Test
    fun `hideKeyboard is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.hideKeyboard() }
    }

    @Test
    fun `isKeyboardVisible is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.isKeyboardVisible() }
    }

    @Test
    fun `swipe (targetless direction) routes to device-core`() {
        // device-core serves a targetless directional screen swipe; the direction maps to a Travel.
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        d.swipe(swipeDirection = SwipeDirection.UP, duration = 400)
        assertThat(provider.lastSwipe).isEqualTo(Travel.UP)
    }

    @Test
    fun `swipe with a start point is not implemented (device-core swipe is targetless)`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        assertThrows<MaestroException.NotImplemented> {
            d.swipe(swipeDirection = SwipeDirection.UP, startPoint = Point(10, 10), duration = 400)
        }
    }

    @Test
    fun `swipe (direction from an explicit start point) is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> {
            d.swipe(SwipeDirection.UP, Point(10, 10), 400, null)
        }
    }

    @Test
    fun `swipeFromCenter is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> {
            d.swipeFromCenter(SwipeDirection.UP, 400, null)
        }
    }

    @Test
    fun `scrollVertical is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.scrollVertical() }
    }

    @Test
    fun `tapOnRelative is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.tapOnRelative(50, 50) }
    }

    @Test
    fun `tapOnPoint is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.tapOnPoint(10, 10) }
    }

    @Test
    fun `waitForAnimationToEnd is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.waitForAnimationToEnd(null) }
    }

    @Test
    fun `waitForAppToSettle is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.waitForAppToSettle() }
    }

    @Test
    fun `openLink routes to device-core`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        d.openLink("https://example.com", null, false, false)
        assertThat(provider.lastOpenedLink).isEqualTo("https://example.com")
    }

    @Test
    fun `openLink with browser=true is not implemented`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }
        val d = driver(provider).apply { connect(DeviceCoreTarget(Platform.ANDROID), null) }
        assertThrows<MaestroException.NotImplemented> {
            d.openLink("https://example.com", null, false, true)
        }
    }

    @Test
    fun `addMedia is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.addMedia(listOf("a.png")) }
    }

    @Test
    fun `clearAppState clears the app through device-core`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }
        val d = driver(provider)
        d.connect(DeviceCoreTarget(Platform.ANDROID), "com.example.example")
        d.clearAppState("com.example.example")
        assertThat(provider.clearedApps).containsExactly("com.example.example")
    }

    @Test
    fun `clearKeychain is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.clearKeychain() }
    }

    @Test
    fun `stopApp is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.stopApp("com.example.example") }
    }

    @Test
    fun `killApp is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.killApp("com.example.example") }
    }

    @Test
    fun `setPermissions passes the grants map through to device-core`() {
        val provider = FakeDeviceProvider { DeviceCoreEvidence.absent("x") }
        val d = driver(provider)
        d.connect(DeviceCoreTarget(Platform.ANDROID), "com.example.example")
        d.setPermissions("com.example.example", mapOf("all" to "allow"))
        assertThat(provider.grantedPermissions)
            .containsExactly("com.example.example" to mapOf("all" to "allow"))
    }

    @Test
    fun `setLocation is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.setLocation("1.0", "2.0") }
    }

    @Test
    fun `setOrientation is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.setOrientation(DeviceOrientation.PORTRAIT) }
    }

    @Test
    fun `setAirplaneModeState is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.setAirplaneModeState(true) }
    }

    @Test
    fun `isAirplaneModeEnabled is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.isAirplaneModeEnabled() }
    }

    @Test
    fun `setDarkModeState is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.setDarkModeState(true) }
    }

    @Test
    fun `isDarkModeEnabled is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.isDarkModeEnabled() }
    }

    @Test
    fun `setAndroidChromeDevToolsEnabled is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.setAndroidChromeDevToolsEnabled(true) }
    }

    @Test
    fun `deviceInfo is not implemented`() {
        val d = unimplementedDriver()
        assertThrows<MaestroException.NotImplemented> { d.deviceInfo() }
    }

    @Test
    fun `a gateway overriding only the built verbs inherits the NotImplemented default`() {
        val minimal = object : DeviceGateway {
            override fun connect(target: DeviceCoreTarget, appId: String?) {}
            override fun close() {}
            override fun launchApp(appId: String, arguments: Map<String, Any>) {}
            override fun tap(selector: ElementSelector, timeoutMs: Long): ChosenElement? = null
            override fun assertVisibility(selector: ElementSelector, mode: AssertMode, timeoutMs: Long): ChosenElement? = null
        }
        val e = assertThrows<MaestroException.NotImplemented> { minimal.inputText("hello") }
        assertThat(e.message).contains("inputText")
    }
}
