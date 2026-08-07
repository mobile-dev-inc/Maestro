package maestro.orchestra.devicecore

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.*
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.Driver
import maestro.Maestro
import maestro.device.Platform
import maestro.orchestra.Condition
import maestro.orchestra.ElementSelector
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension

@ExtendWith(SystemStubsExtension::class)
class DeviceCoreAssertRouterTest {
    @SystemStub
    private val environmentVariables = EnvironmentVariables()

    @AfterEach
    fun clearGlobalState() {
        System.clearProperty("devicecore.ios.bundleId")
        System.clearProperty("devicecore.android.forwardPort")
    }

    private fun mockMaestro(platform: Platform): Maestro = mockk {
        every { cachedDeviceInfo } returns DeviceInfo(
            platform = platform,
            widthPixels = 1080,
            heightPixels = 2400,
            widthGrid = 1080,
            heightGrid = 2400,
        )
        every { driver } returns mockk(relaxed = true)
    }

    /** Records releaseSlot/reacquireSlot calls, in order, into [order]. */
    private fun fakeDriverRecording(order: MutableList<String>): Driver = mockk(relaxed = true) {
        every { releaseSlot() } answers { order += "releaseSlot" }
        every { reacquireSlot() } answers { order += "reacquireSlot" }
    }

    private fun androidRouter(driver: Driver, provider: DeviceProvider) = DeviceCoreAssertRouter(
        appId = "org.wikipedia",
        platform = Platform.ANDROID,
        target = TargetId.ANDROID_EMU,
        driver = driver,
        providerFactory = { provider },
    )

    private val ua = Signal(false, EvidenceSource.UNAVAILABLE)
    private fun resolved(x: Int, y: Int, w: Int, h: Int) = ElementEvidence(
        "t", Resolution.Resolved(ResolvedChannel.TEXT),
        Actionability(ua, ua, Signal(true, EvidenceSource.MEASURED), ua, ua),
        Sourced(Rect(x, y, w, h), EvidenceSource.MEASURED),
    )
    private fun absent() = ElementEvidence(
        "t", Resolution.Absent(SearchedSurface.WHOLE_SCREEN),
        Actionability(ua, ua, ua, ua, ua), Sourced(null, EvidenceSource.UNAVAILABLE),
    )

    @Test fun `canRoute mirrors DeviceCoreRouting`() {
        val r = DeviceCoreAssertRouter("com.x") { FakeDeviceProvider { resolved(1,1,10,10) } }
        assertThat(r.canRoute(Condition(visible = ElementSelector(textRegex = "Hi")))).isTrue()
        assertThat(r.canRoute(Condition(visible = ElementSelector(idRegex = "hi")))).isFalse()
    }

    @Test fun `evaluate visible on a resolved on-screen element returns true, targets IOS_SIM by text`() {
        val fake = FakeDeviceProvider { resolved(122, 160, 148, 26) }
        val r = DeviceCoreAssertRouter("com.x") { fake }
        val pass = runBlocking { r.evaluate(Condition(visible = ElementSelector(textRegex = "Welcome")), 393, 852) }
        assertThat(pass).isTrue()
        assertThat(fake.lastConnectedTarget?.target).isEqualTo(TargetId.IOS_SIM)
        assertThat(fake.lastSelector).isEqualTo(Selector.Text("Welcome", Match.EXACT))
        assertThat(System.getProperty("devicecore.ios.bundleId")).isEqualTo("com.x")
        // Set-before-connect ordering, not just final presence: the property must already be
        // "com.x" at the moment connect() runs.
        assertThat(fake.bundleIdAtConnect).isEqualTo("com.x")
    }

    @Test fun `evaluate visible on an absent element returns false (the negative control)`() {
        val r = DeviceCoreAssertRouter("com.x") { FakeDeviceProvider { absent() } }
        val pass = runBlocking { r.evaluate(Condition(visible = ElementSelector(textRegex = "Nope")), 393, 852) }
        assertThat(pass).isFalse()
    }

    @Test fun `evaluate applies nth for an indexed selector`() {
        val fake = FakeDeviceProvider { resolved(1, 1, 10, 10) }
        val r = DeviceCoreAssertRouter("com.x") { fake }
        runBlocking { r.evaluate(Condition(visible = ElementSelector(textRegex = "Row", index = "2")), 393, 852) }
        assertThat(fake.lastSelector).isEqualTo(Selector.Nth(Selector.Text("Row", Match.EXACT), 2))
    }

    @Test fun `evaluate wraps a plain infra exception from inspect() as DeviceCoreUnavailable`() {
        val fake = FakeDeviceProvider { throw RuntimeException("socket refused") }
        val r = DeviceCoreAssertRouter("com.x") { fake }
        val thrown = assertThrows<DeviceCoreUnavailable> {
            runBlocking { r.evaluate(Condition(visible = ElementSelector(textRegex = "Hi")), 393, 852) }
        }
        assertThat(thrown.message).contains("socket refused")
    }

    @Test fun `evaluate propagates a DeviceCoreUnavailable from inspect() without double-wrapping`() {
        val original = DeviceCoreUnavailable("driver down")
        val fake = FakeDeviceProvider { throw original }
        val r = DeviceCoreAssertRouter("com.x") { fake }
        val thrown = assertThrows<DeviceCoreUnavailable> {
            runBlocking { r.evaluate(Condition(visible = ElementSelector(textRegex = "Hi")), 393, 852) }
        }
        assertThat(thrown).isSameInstanceAs(original)
        assertThat(thrown.message).isEqualTo("driver down")
    }

    @Test fun `evaluate throws IllegalArgumentException for a non-routable condition`() {
        val r = DeviceCoreAssertRouter("com.x") { FakeDeviceProvider { resolved(1, 1, 10, 10) } }
        assertThrows<IllegalArgumentException> {
            runBlocking { r.evaluate(Condition(visible = ElementSelector(idRegex = "x")), 393, 852) }
        }
    }

    @Test fun `evaluate notVisible on an absent element returns true`() {
        val r = DeviceCoreAssertRouter("com.x") { FakeDeviceProvider { absent() } }
        val pass = runBlocking {
            r.evaluate(Condition(notVisible = ElementSelector(textRegex = "Spinner")), 393, 852)
        }
        assertThat(pass).isTrue()
    }

    @Test fun `evaluate notVisible on a resolved on-screen element returns false`() {
        val r = DeviceCoreAssertRouter("com.x") { FakeDeviceProvider { resolved(122, 160, 148, 26) } }
        val pass = runBlocking {
            r.evaluate(Condition(notVisible = ElementSelector(textRegex = "Spinner")), 393, 852)
        }
        assertThat(pass).isFalse()
    }

    @Test fun `an Android-configured router targets ANDROID_EMU and sets forwardPort, not bundleId`() {
        val fake = FakeDeviceProvider { resolved(122, 160, 148, 26) }
        val r = DeviceCoreAssertRouter(
            appId = "org.wikipedia",
            providerFactory = { fake },
            platform = Platform.ANDROID,
            target = TargetId.ANDROID_EMU,
        )
        val pass = runBlocking {
            r.evaluate(Condition(visible = ElementSelector(textRegex = "Search Wikipedia")), 1080, 2400)
        }
        assertThat(pass).isTrue()
        assertThat(fake.lastConnectedTarget?.target).isEqualTo(TargetId.ANDROID_EMU)
        assertThat(System.getProperty("devicecore.android.forwardPort")).isEqualTo("8791")
        assertThat(System.getProperty("devicecore.ios.bundleId")).isNull()
    }

    @Test fun `evaluate releases before inspect and reacquires even when inspect throws`() {
        val order = mutableListOf<String>()
        val driver = fakeDriverRecording(order)
        val throwingProvider = FakeDeviceProvider { order += "inspect"; error("boom") }
        val router = androidRouter(driver = driver, provider = throwingProvider)

        assertThrows<DeviceCoreUnavailable> {
            runBlocking { router.evaluate(Condition(visible = ElementSelector(textRegex = "X")), 1080, 2400) }
        }

        assertThat(order).isEqualTo(listOf("releaseSlot", "inspect", "reacquireSlot"))
    }

    @Test fun `evaluate releases before inspect and reacquires after, on the happy path`() {
        val order = mutableListOf<String>()
        val driver = fakeDriverRecording(order)
        val provider = FakeDeviceProvider { order += "inspect"; resolved(122, 160, 148, 26) }
        val router = androidRouter(driver = driver, provider = provider)

        val pass = runBlocking {
            router.evaluate(Condition(visible = ElementSelector(textRegex = "Search Wikipedia")), 1080, 2400)
        }

        assertThat(pass).isTrue()
        assertThat(order).isEqualTo(listOf("releaseSlot", "inspect", "reacquireSlot"))
    }

    @Test fun `fromEnvOrNull returns an Android router when the gate is on and platform is ANDROID`() {
        environmentVariables.set("MAESTRO_DEVICECORE_ASSERT", "1")
        val maestro = mockMaestro(Platform.ANDROID)

        val router = DeviceCoreAssertRouter.fromEnvOrNull(maestro, "org.wikipedia")

        assertThat(router).isNotNull()
    }

    @Test fun `fromEnvOrNull returns null when the env gate is off, even on ANDROID`() {
        environmentVariables.set("MAESTRO_DEVICECORE_ASSERT", "0")
        val maestro = mockMaestro(Platform.ANDROID)

        val router = DeviceCoreAssertRouter.fromEnvOrNull(maestro, "org.wikipedia")

        assertThat(router).isNull()
    }

    @Test fun `fromEnvOrNull returns null for WEB even when the gate is on`() {
        environmentVariables.set("MAESTRO_DEVICECORE_ASSERT", "1")
        val maestro = mockMaestro(Platform.WEB)

        val router = DeviceCoreAssertRouter.fromEnvOrNull(maestro, "org.wikipedia")

        assertThat(router).isNull()
    }
}
