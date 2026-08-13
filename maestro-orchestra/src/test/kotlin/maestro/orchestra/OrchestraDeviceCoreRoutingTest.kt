package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.Selector
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.KeyCode
import maestro.Maestro
import maestro.MaestroException
import maestro.Point
import maestro.ScreenRecording
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
 * W1.3: the three BUILT verbs (launchApp / selector-tap / assertVisibility) must route through the
 * [DeviceCoreDriver] seam, not through the legacy `maestro.*` matching engine, and the per-modifier
 * guards [DeviceCoreFlowRunner] enforced must be folded into the Orchestra handlers verbatim.
 *
 * The fake driver records the five real verbs and translates selectors through the SAME
 * [SelectorTranslator] the real driver uses — so an unsupported selector field throws
 * [MaestroException.NotImplemented] here exactly as it would on device. Every roadmap verb is
 * radioactive: if Orchestra reaches for one, the test fails loudly rather than silently no-op'ing.
 */
class OrchestraDeviceCoreRoutingTest {

    /** Records the five vertical verbs; translates selectors via [SelectorTranslator] like the real driver. */
    private class RecordingDeviceCoreDriver(
        /** Injected verdict for [assertVisibility]: throw an AssertionFailure to simulate a false verdict. */
        private val onAssert: (ElementSelector, AssertMode) -> Unit = { _, _ -> },
    ) : DeviceCoreDriver {
        val launched = mutableListOf<String>()
        val tapped = mutableListOf<ElementSelector>()
        val tappedSelectors = mutableListOf<Selector>()
        val asserted = mutableListOf<Pair<ElementSelector, AssertMode>>()
        val assertedSelectors = mutableListOf<Selector>()

        override fun connect(target: DeviceCoreTarget, appId: String?) {}
        override fun close() {}

        override fun launchApp(appId: String) {
            launched += appId
        }

        override fun tap(selector: ElementSelector): ChosenElement? {
            tappedSelectors += SelectorTranslator.translate(selector)
            tapped += selector
            return null
        }

        override fun assertVisibility(selector: ElementSelector, mode: AssertMode): ChosenElement? {
            assertedSelectors += SelectorTranslator.translate(selector)
            asserted += selector to mode
            onAssert(selector, mode)
            return null
        }

        private fun boom(verb: String): Nothing =
            throw AssertionError("W1.3 must not route a built verb onto roadmap DeviceCoreDriver.$verb")

        override fun hierarchy(): Nothing = boom("hierarchy")
        override fun takeScreenshot(out: Sink, compressed: Boolean, cropOn: ElementSelector?) = boom("takeScreenshot")
        override fun startScreenRecording(out: Sink): ScreenRecording = boom("startScreenRecording")
        override fun inputText(text: String) = boom("inputText")
        override fun eraseText(charactersToErase: Int) = boom("eraseText")
        override fun pressKey(code: KeyCode, waitForAppToSettle: Boolean) = boom("pressKey")
        override fun backPress() = boom("backPress")
        override fun hideKeyboard() = boom("hideKeyboard")
        override fun isKeyboardVisible(): Boolean = boom("isKeyboardVisible")
        override fun swipe(
            swipeDirection: SwipeDirection?,
            startPoint: Point?,
            endPoint: Point?,
            startRelative: String?,
            endRelative: String?,
            duration: Long,
            waitToSettleTimeoutMs: Int?,
        ) = boom("swipe")
        override fun swipe(swipeDirection: SwipeDirection, startPoint: Point, durationMs: Long, waitToSettleTimeoutMs: Int?) =
            boom("swipe")
        override fun swipeFromCenter(swipeDirection: SwipeDirection, durationMs: Long, waitToSettleTimeoutMs: Int?) =
            boom("swipeFromCenter")
        override fun scrollVertical() = boom("scrollVertical")
        override fun tapOnRelative(
            percentX: Int,
            percentY: Int,
            retryIfNoChange: Boolean,
            longPress: Boolean,
            tapRepeat: TapRepeat?,
            waitToSettleTimeoutMs: Int?,
        ) = boom("tapOnRelative")
        override fun tapOnPoint(
            x: Int,
            y: Int,
            retryIfNoChange: Boolean,
            longPress: Boolean,
            tapRepeat: TapRepeat?,
            waitToSettleTimeoutMs: Int?,
        ) = boom("tapOnPoint")
        override fun waitForAnimationToEnd(timeout: String?) = boom("waitForAnimationToEnd")
        override fun waitForAppToSettle(appId: String?, waitToSettleTimeoutMs: Int?) = boom("waitForAppToSettle")
        override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) = boom("openLink")
        override fun addMedia(fileNames: List<String>) = boom("addMedia")
        override fun clearAppState(appId: String) = boom("clearAppState")
        override fun clearKeychain() = boom("clearKeychain")
        override fun stopApp(appId: String) = boom("stopApp")
        override fun killApp(appId: String) = boom("killApp")
        override fun setPermissions(appId: String, permissions: Map<String, String>) = boom("setPermissions")
        override fun setLocation(latitude: String, longitude: String) = boom("setLocation")
        override fun setOrientation(orientation: DeviceOrientation, waitForAppToSettle: Boolean) = boom("setOrientation")
        override fun setAirplaneModeState(enabled: Boolean) = boom("setAirplaneModeState")
        override fun isAirplaneModeEnabled(): Boolean = boom("isAirplaneModeEnabled")
        override fun setDarkModeState(enabled: Boolean) = boom("setDarkModeState")
        override fun isDarkModeEnabled(): Boolean = boom("isDarkModeEnabled")
        override fun setAndroidChromeDevToolsEnabled(enabled: Boolean) = boom("setAndroidChromeDevToolsEnabled")
        override fun deviceInfo(): DeviceInfo = boom("deviceInfo")
    }

    private fun orchestra(driver: DeviceCoreDriver): Orchestra = Orchestra(
        maestro = mockk(relaxed = true),
        driver = driver,
        platform = Platform.ANDROID,
    )

    private fun run(driver: DeviceCoreDriver, vararg commands: MaestroCommand) =
        runBlocking { orchestra(driver).runFlow(commands.toList()) }

    // --- Repoint 1: launchApp ---

    @Test
    fun `launchApp routes the interpolated appId through the driver`() {
        val driver = RecordingDeviceCoreDriver()
        // JS interpolation proves the appId reaching the seam is the evaluated one, not the raw template.
        val result = run(driver, MaestroCommand(launchAppCommand = LaunchAppCommand(appId = "\${'com.example.' + 'interpolated'}")))

        assertThat(result.success).isTrue()
        assertThat(driver.launched).containsExactly("com.example.interpolated")
    }

    @Test
    fun `launchApp with launchArguments throws NotImplemented with the runner's message`() {
        val driver = RecordingDeviceCoreDriver()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(launchAppCommand = LaunchAppCommand(appId = "com.example.app", launchArguments = mapOf("foo" to "bar"))))
        }
        assertThat(e.message).isEqualTo("launchApp modifier launchArguments")
        assertThat(driver.launched).isEmpty()
    }

    @Test
    fun `launchApp with stopApp=false throws NotImplemented with the runner's message`() {
        val driver = RecordingDeviceCoreDriver()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(launchAppCommand = LaunchAppCommand(appId = "com.example.app", stopApp = false)))
        }
        assertThat(e.message).isEqualTo("launchApp modifier stopApp")
        assertThat(driver.launched).isEmpty()
    }

    // --- Repoint 2: selector tap ---

    @Test
    fun `tapOn by id routes the translated selector through the driver`() {
        val driver = RecordingDeviceCoreDriver()
        val result = run(driver, MaestroCommand(tapOnElement = TapOnElementCommand(selector = ElementSelector(idRegex = "fabAddIcon"))))

        assertThat(result.success).isTrue()
        assertThat(driver.tapped).hasSize(1)
        assertThat(driver.tappedSelectors).containsExactly(Selector.Id("fabAddIcon"))
    }

    @Test
    fun `tapOn with longPress throws NotImplemented with the runner's message`() {
        val driver = RecordingDeviceCoreDriver()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(tapOnElement = TapOnElementCommand(selector = ElementSelector(idRegex = "x"), longPress = true)))
        }
        assertThat(e.message).isEqualTo("tapOnElement modifier longPress")
        assertThat(driver.tapped).isEmpty()
    }

    @Test
    fun `tapOn with repeat throws NotImplemented with the runner's message`() {
        val driver = RecordingDeviceCoreDriver()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(tapOnElement = TapOnElementCommand(selector = ElementSelector(idRegex = "x"), repeat = TapRepeat(repeat = 2, delay = 0))))
        }
        assertThat(e.message).isEqualTo("tapOnElement modifier repeat")
        assertThat(driver.tapped).isEmpty()
    }

    // --- Repoint 3: assertVisibility ---

    @Test
    fun `assertVisible routes through the driver with VISIBLE mode on a passing verdict`() {
        val driver = RecordingDeviceCoreDriver()
        val result = run(driver, MaestroCommand(assertConditionCommand = AssertConditionCommand(Condition(visible = ElementSelector(textRegex = "Form Test")))))

        assertThat(result.success).isTrue()
        assertThat(driver.asserted).hasSize(1)
        assertThat(driver.asserted.single().second).isEqualTo(AssertMode.VISIBLE)
        assertThat(driver.assertedSelectors).containsExactly(Selector.Text("Form Test", dev.mobile.devicecore.prototype.api.Match.PATTERN, ignoreCase = true))
    }

    @Test
    fun `assertNotVisible routes through the driver with NOT_VISIBLE mode on a passing verdict`() {
        val driver = RecordingDeviceCoreDriver()
        val result = run(driver, MaestroCommand(assertConditionCommand = AssertConditionCommand(Condition(notVisible = ElementSelector(textRegex = "kwyjibo")))))

        assertThat(result.success).isTrue()
        assertThat(driver.asserted.single().second).isEqualTo(AssertMode.NOT_VISIBLE)
    }

    @Test
    fun `assertVisible surfaces a failing verdict as an AssertionFailure`() {
        // Driver's own false verdict throws AssertionFailure; the assert command must fail the flow.
        val driver = RecordingDeviceCoreDriver(onAssert = { selector, _ ->
            throw MaestroException.AssertionFailure(
                message = "Assertion is false: ${selector.description()} is visible",
                hierarchyRoot = maestro.TreeNode(),
                debugMessage = "fake driver false verdict",
            )
        })
        assertThrows(MaestroException.AssertionFailure::class.java) {
            run(driver, MaestroCommand(assertConditionCommand = AssertConditionCommand(Condition(visible = ElementSelector(textRegex = "Missing")))))
        }
        assertThat(driver.asserted.single().second).isEqualTo(AssertMode.VISIBLE)
    }

    // --- Repoint 3: when: guards ---

    @Test
    fun `a platform when guard resolves from the session without touching any seam verb`() {
        // AlwaysThrows-style driver: if platform resolution reached any verb, the flow would blow up.
        val driver = RecordingDeviceCoreDriver()
        val leaf = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val ran = mutableListOf<String>()
        val command = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(leaf),
                condition = Condition(platform = Platform.ANDROID),
                config = null,
            ),
        )
        val orchestra = Orchestra(
            maestro = mockk(relaxed = true),
            driver = driver,
            platform = Platform.ANDROID,
            onCommandComplete = { _, cmd -> if (cmd == leaf) ran += "ran" },
        )

        val result = runBlocking { orchestra.runFlow(listOf(command)) }

        assertThat(result.success).isTrue()
        assertThat(ran).containsExactly("ran")
        assertThat(driver.asserted).isEmpty()
        assertThat(driver.tapped).isEmpty()
    }

    @Test
    fun `a visibility when guard on a roadmap-only selector throws NotImplemented, never a silent verdict`() {
        val driver = RecordingDeviceCoreDriver()
        val leaf = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val command = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(leaf),
                // `css` is a roadmap selector field SelectorTranslator rejects at the seam.
                condition = Condition(visible = ElementSelector(css = "div.foo")),
                config = null,
            ),
        )
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            runBlocking { orchestra(driver).runFlow(listOf(command)) }
        }
        assertThat(e.message).contains("css")
    }
}
