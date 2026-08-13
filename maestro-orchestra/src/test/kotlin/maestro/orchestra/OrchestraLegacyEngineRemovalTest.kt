package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.KeyCode
import maestro.MaestroException
import maestro.Point
import maestro.ScreenRecording
import maestro.ScrollDirection
import maestro.SwipeDirection
import maestro.TapRepeat
import maestro.device.DeviceOrientation
import maestro.device.Platform
import maestro.orchestra.devicecore.AssertMode
import maestro.orchestra.devicecore.ChosenElement
import maestro.orchestra.devicecore.DeviceCoreDriver
import maestro.orchestra.devicecore.DeviceCoreTarget
import maestro.orchestra.devicecore.SelectorTranslator
import okio.Sink
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * W1.4: the legacy on-device matching engine (`findElement` + `buildFilter` + helpers) is deleted.
 * Every former caller now resolves elements through the [DeviceCoreDriver] seam, so it either works
 * (id/text via `SelectorTranslator`) or surfaces [MaestroException.NotImplemented] — through the
 * translator (unsupported selector fields) or a roadmap seam verb (bounds / text extraction /
 * scroll-poll / element-anchored swipe / element-relative point).
 *
 * The fake driver mirrors [maestro.orchestra.devicecore.RealDeviceCoreDriver]: the built verbs
 * (launch / tap / assertVisibility) work and translate selectors through the SAME
 * [SelectorTranslator] the real driver uses, and every roadmap verb throws NotImplemented exactly as
 * the real driver would on device. That is what lets these tests assert the real coverage-map
 * behavior with no emulator.
 */
class OrchestraLegacyEngineRemovalTest {

    /** Built verbs work (translating like the real driver); roadmap verbs throw NotImplemented. */
    private class SeamFakeDriver : DeviceCoreDriver {
        val tapped = mutableListOf<ElementSelector>()
        val asserted = mutableListOf<Pair<ElementSelector, AssertMode>>()

        override fun connect(target: DeviceCoreTarget, appId: String?) {}
        override fun close() {}
        override fun launchApp(appId: String) {}

        override fun tap(selector: ElementSelector): ChosenElement? {
            SelectorTranslator.translate(selector) // unsupported fields throw NotImplemented, as on device
            tapped += selector
            return null
        }

        override fun assertVisibility(selector: ElementSelector, mode: AssertMode): ChosenElement? {
            SelectorTranslator.translate(selector)
            asserted += selector to mode
            return null
        }

        private fun roadmap(verb: String): Nothing =
            throw MaestroException.NotImplemented("device-core driver does not yet implement $verb")

        override fun hierarchy(): Nothing = roadmap("hierarchy")
        override fun takeScreenshot(out: Sink, compressed: Boolean, cropOn: ElementSelector?) = roadmap("takeScreenshot")
        override fun startScreenRecording(out: Sink): ScreenRecording = roadmap("startScreenRecording")
        override fun inputText(text: String) = roadmap("inputText")
        override fun eraseText(charactersToErase: Int) = roadmap("eraseText")
        override fun pressKey(code: KeyCode, waitForAppToSettle: Boolean) = roadmap("pressKey")
        override fun backPress() = roadmap("backPress")
        override fun hideKeyboard() = roadmap("hideKeyboard")
        override fun isKeyboardVisible(): Boolean = roadmap("isKeyboardVisible")
        override fun swipe(
            swipeDirection: SwipeDirection?,
            startPoint: Point?,
            endPoint: Point?,
            startRelative: String?,
            endRelative: String?,
            duration: Long,
            waitToSettleTimeoutMs: Int?,
        ) = roadmap("swipe")
        override fun swipe(swipeDirection: SwipeDirection, startPoint: Point, durationMs: Long, waitToSettleTimeoutMs: Int?) =
            roadmap("swipe")
        override fun swipeFromCenter(swipeDirection: SwipeDirection, durationMs: Long, waitToSettleTimeoutMs: Int?) =
            roadmap("swipeFromCenter")
        override fun scrollVertical() = roadmap("scrollVertical")
        override fun tapOnRelative(
            percentX: Int,
            percentY: Int,
            retryIfNoChange: Boolean,
            longPress: Boolean,
            tapRepeat: TapRepeat?,
            waitToSettleTimeoutMs: Int?,
        ) = roadmap("tapOnRelative")
        override fun tapOnPoint(
            x: Int,
            y: Int,
            retryIfNoChange: Boolean,
            longPress: Boolean,
            tapRepeat: TapRepeat?,
            waitToSettleTimeoutMs: Int?,
        ) = roadmap("tapOnPoint")
        override fun waitForAnimationToEnd(timeout: String?) = roadmap("waitForAnimationToEnd")
        override fun waitForAppToSettle(appId: String?, waitToSettleTimeoutMs: Int?) = roadmap("waitForAppToSettle")
        override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) = roadmap("openLink")
        override fun addMedia(fileNames: List<String>) = roadmap("addMedia")
        override fun clearAppState(appId: String) = roadmap("clearAppState")
        override fun clearKeychain() = roadmap("clearKeychain")
        override fun stopApp(appId: String) = roadmap("stopApp")
        override fun killApp(appId: String) = roadmap("killApp")
        override fun setPermissions(appId: String, permissions: Map<String, String>) = roadmap("setPermissions")
        override fun setLocation(latitude: String, longitude: String) = roadmap("setLocation")
        override fun setOrientation(orientation: DeviceOrientation, waitForAppToSettle: Boolean) = roadmap("setOrientation")
        override fun setAirplaneModeState(enabled: Boolean) = roadmap("setAirplaneModeState")
        override fun isAirplaneModeEnabled(): Boolean = roadmap("isAirplaneModeEnabled")
        override fun setDarkModeState(enabled: Boolean) = roadmap("setDarkModeState")
        override fun isDarkModeEnabled(): Boolean = roadmap("isDarkModeEnabled")
        override fun setAndroidChromeDevToolsEnabled(enabled: Boolean) = roadmap("setAndroidChromeDevToolsEnabled")
        override fun deviceInfo(): DeviceInfo = roadmap("deviceInfo")
    }

    private fun orchestra(driver: DeviceCoreDriver): Orchestra = Orchestra(
        maestro = mockk(relaxed = true),
        driver = driver,
        platform = Platform.ANDROID,
    )

    private fun run(driver: DeviceCoreDriver, vararg commands: MaestroCommand) =
        runBlocking { orchestra(driver).runFlow(commands.toList()) }

    // --- A former-findElement id/text assert still resolves through the seam ---

    @Test
    fun `an id assert resolves through the driver and passes`() {
        val driver = SeamFakeDriver()
        val result = run(
            driver,
            MaestroCommand(assertConditionCommand = AssertConditionCommand(Condition(visible = ElementSelector(idRegex = "login")))),
        )
        assertThat(result.success).isTrue()
        assertThat(driver.asserted.single().second).isEqualTo(AssertMode.VISIBLE)
    }

    // --- Unsupported selector families route through the seam and throw NotImplemented (via SelectorTranslator) ---

    @Test
    fun `tapOn with a relative-locator selector throws NotImplemented naming the field`() {
        val driver = SeamFakeDriver()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(tapOnElement = TapOnElementCommand(
                selector = ElementSelector(idRegex = "x", below = ElementSelector(textRegex = "Header")))))
        }
        assertThat(e.message).contains("below")
        assertThat(driver.tapped).isEmpty()
    }

    @Test
    fun `tapOn with a childOf selector throws NotImplemented naming the field`() {
        val driver = SeamFakeDriver()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(tapOnElement = TapOnElementCommand(
                selector = ElementSelector(idRegex = "x", childOf = ElementSelector(idRegex = "container")))))
        }
        assertThat(e.message).contains("childOf")
        assertThat(driver.tapped).isEmpty()
    }

    @Test
    fun `tapOn with a traits selector throws NotImplemented naming the field`() {
        val driver = SeamFakeDriver()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(tapOnElement = TapOnElementCommand(
                selector = ElementSelector(idRegex = "x", traits = listOf(ElementTrait.TEXT)))))
        }
        assertThat(e.message).contains("traits")
        assertThat(driver.tapped).isEmpty()
    }

    @Test
    fun `tapOn with a css selector throws NotImplemented naming the field`() {
        val driver = SeamFakeDriver()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(tapOnElement = TapOnElementCommand(
                selector = ElementSelector(css = "div.foo"))))
        }
        assertThat(e.message).contains("css")
        assertThat(driver.tapped).isEmpty()
    }

    @Test
    fun `tapOn with a containsChild selector throws NotImplemented naming the field`() {
        val driver = SeamFakeDriver()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(tapOnElement = TapOnElementCommand(
                selector = ElementSelector(idRegex = "x", containsChild = ElementSelector(textRegex = "child")))))
        }
        assertThat(e.message).contains("containsChild")
        assertThat(driver.tapped).isEmpty()
    }

    // --- Roadmap capabilities: bounds / point / text / scroll-poll surface NotImplemented, never crash or no-op ---

    @Test
    fun `tapOn with an element-relative point throws NotImplemented for the modifier`() {
        val driver = SeamFakeDriver()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(tapOnElement = TapOnElementCommand(
                selector = ElementSelector(idRegex = "x"), relativePoint = "90%,90%")))
        }
        assertThat(e.message).isEqualTo("tapOnElement modifier relativePoint")
        assertThat(driver.tapped).isEmpty()
    }

    @Test
    fun `copyTextFrom throws NotImplemented instead of silently copying empty text`() {
        val driver = SeamFakeDriver()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(copyTextCommand = CopyTextFromCommand(selector = ElementSelector(idRegex = "field"))))
        }
        assertThat(e.message).contains("hierarchy")
    }

    @Test
    fun `scrollUntilVisible throws NotImplemented instead of silently no-oping`() {
        val driver = SeamFakeDriver()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(scrollUntilVisible = ScrollUntilVisibleCommand(
                selector = ElementSelector(idRegex = "target"),
                direction = ScrollDirection.DOWN,
                visibilityPercentage = 100,
                centerElement = false,
            )))
        }
        assertThat(e.message).contains("swipeFromCenter")
    }

    @Test
    fun `swipe from a resolved element throws NotImplemented rather than crashing on a missing element`() {
        val driver = SeamFakeDriver()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(swipeCommand = SwipeCommand(
                elementSelector = ElementSelector(idRegex = "list"),
                direction = SwipeDirection.UP,
            )))
        }
        assertThat(e.message).contains("swipe")
    }
}
