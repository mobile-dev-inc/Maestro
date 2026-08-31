package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import dev.mobile.devicecore.prototype.api.Selector
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.KeyCode
import maestro.MaestroException
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TapRepeat
import maestro.device.DeviceOrientation
import maestro.device.Platform
import maestro.orchestra.devicecore.AssertMode
import maestro.orchestra.devicecore.ChosenElement
import maestro.orchestra.devicecore.DeviceGateway
import maestro.orchestra.devicecore.DeviceCoreTarget
import maestro.orchestra.devicecore.RealDeviceGateway
import maestro.orchestra.devicecore.SelectorTranslator
import okio.Sink
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * W1.3: the three BUILT verbs (launchApp / selector-tap / assertVisibility) must route through the
 * [DeviceGateway] seam, not through the legacy `maestro.*` matching engine, and the per-modifier
 * guards [DeviceCoreFlowRunner] enforced must be folded into the Orchestra handlers verbatim.
 *
 * The fake driver records the five real verbs and translates selectors through the SAME
 * [SelectorTranslator] the real driver uses — so an unsupported selector field throws
 * [MaestroException.NotImplemented] here exactly as it would on device. Every roadmap verb is
 * radioactive: if Orchestra reaches for one, the test fails loudly rather than silently no-op'ing.
 */
class OrchestraDeviceCoreRoutingTest {

    /** Records the five vertical verbs; translates selectors via [SelectorTranslator] like the real driver. */
    private class RecordingDeviceGateway(
        /** Injected verdict for [assertVisibility]: throw an AssertionFailure to simulate a false verdict. */
        private val onAssert: (ElementSelector, AssertMode) -> Unit = { _, _ -> },
    ) : DeviceGateway {
        val launched = mutableListOf<String>()
        val launchArgs = mutableListOf<Map<String, Any>>()
        val tapped = mutableListOf<ElementSelector>()
        val tappedSelectors = mutableListOf<Selector>()
        val longPressed = mutableListOf<ElementSelector>()
        val inputTexts = mutableListOf<String>()
        val swipes = mutableListOf<SwipeDirection>()
        val openedLinks = mutableListOf<String>()
        val permissionsGranted = mutableListOf<Pair<String, Map<String, String>>>()
        var backPresses = 0
        val asserted = mutableListOf<Pair<ElementSelector, AssertMode>>()
        val assertedSelectors = mutableListOf<Selector>()
        val assertedTimeouts = mutableListOf<Long>()

        override fun connect(target: DeviceCoreTarget, appId: String?) {}
        override fun close() {}

        override fun launchApp(appId: String, arguments: Map<String, Any>) {
            launched += appId
            launchArgs += arguments
        }

        override fun tap(selector: ElementSelector, timeoutMs: Long): ChosenElement? {
            tappedSelectors += SelectorTranslator.translate(selector)
            tapped += selector
            return null
        }

        override fun assertVisibility(selector: ElementSelector, mode: AssertMode, timeoutMs: Long): ChosenElement? {
            assertedSelectors += SelectorTranslator.translate(selector)
            asserted += selector to mode
            assertedTimeouts += timeoutMs
            onAssert(selector, mode)
            return null
        }

        private fun boom(verb: String): Nothing =
            throw AssertionError("W1.3 must not route a built verb onto roadmap DeviceGateway.$verb")

        override fun hierarchy(): Nothing = boom("hierarchy")
        override fun takeScreenshot(out: Sink, compressed: Boolean, cropOn: ElementSelector?) = boom("takeScreenshot")
        override fun startScreenRecording(out: Sink): ScreenRecording = boom("startScreenRecording")
        override fun longPress(selector: ElementSelector, timeoutMs: Long): ChosenElement? { longPressed += selector; return null }
        override fun inputText(text: String) { inputTexts += text }
        override fun eraseText(charactersToErase: Int) = boom("eraseText")
        override fun pressKey(code: KeyCode, waitForAppToSettle: Boolean) = boom("pressKey")
        override fun backPress() { backPresses++ }
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
        ) { swipes += swipeDirection ?: boom("swipe without direction") }
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
        override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) { openedLinks += link }
        override fun addMedia(fileNames: List<String>) = boom("addMedia")
        override fun clearAppState(appId: String) = boom("clearAppState")
        override fun clearKeychain() = boom("clearKeychain")
        override fun stopApp(appId: String) = boom("stopApp")
        override fun killApp(appId: String) = boom("killApp")
        override fun setPermissions(appId: String, permissions: Map<String, String>) { permissionsGranted += appId to permissions }
        override fun setLocation(latitude: String, longitude: String) = boom("setLocation")
        override fun setOrientation(orientation: DeviceOrientation, waitForAppToSettle: Boolean) = boom("setOrientation")
        override fun setAirplaneModeState(enabled: Boolean) = boom("setAirplaneModeState")
        override fun isAirplaneModeEnabled(): Boolean = boom("isAirplaneModeEnabled")
        override fun setDarkModeState(enabled: Boolean) = boom("setDarkModeState")
        override fun isDarkModeEnabled(): Boolean = boom("isDarkModeEnabled")
        override fun setAndroidChromeDevToolsEnabled(enabled: Boolean) = boom("setAndroidChromeDevToolsEnabled")
        override fun deviceInfo(): DeviceInfo = boom("deviceInfo")
    }

    private fun orchestra(driver: DeviceGateway): Orchestra = Orchestra(
        driver = driver,
        platform = Platform.ANDROID,
    )

    private fun run(driver: DeviceGateway, vararg commands: MaestroCommand) =
        runBlocking { orchestra(driver).runFlow(commands.toList()) }

    // --- Repoint 1: launchApp ---

    @Test
    fun `launchApp routes the interpolated appId through the driver`() {
        val driver = RecordingDeviceGateway()
        // JS interpolation proves the appId reaching the seam is the evaluated one, not the raw template.
        val result = run(driver, MaestroCommand(launchAppCommand = LaunchAppCommand(appId = "\${'com.example.' + 'interpolated'}")))

        assertThat(result.success).isTrue()
        assertThat(driver.launched).containsExactly("com.example.interpolated")
    }

    @Test
    fun `launchApp threads launchArguments through the seam verbatim`() {
        // device-core's Device.launchApp(appId, arguments) is served (typed `am start` extras) —
        // the modifier guard retired with it. Map<String, Any> passes through untranslated.
        val driver = RecordingDeviceGateway()
        val result = run(driver, MaestroCommand(launchAppCommand = LaunchAppCommand(appId = "com.example.app", launchArguments = mapOf("foo" to "bar"))))

        assertThat(result.success).isTrue()
        assertThat(driver.launched).containsExactly("com.example.app")
        assertThat(driver.launchArgs).containsExactly(mapOf<String, Any>("foo" to "bar"))
    }

    @Test
    fun `launchApp with stopApp=false throws NotImplemented with the runner's message`() {
        val driver = RecordingDeviceGateway()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(launchAppCommand = LaunchAppCommand(appId = "com.example.app", stopApp = false)))
        }
        assertThat(e.message).isEqualTo("launchApp modifier stopApp")
        assertThat(driver.launched).isEmpty()
    }

    @Test
    fun `launchApp with clearKeychain on Android skips the modifier and launches - legacy no-op semantics`() {
        // Legacy Android's clearKeychain() is a literal no-op, and device-core ships no Android
        // realization (ROADMAP: iOS only). The faithful translation is NO seam call at all: a
        // RecordingDeviceGateway booms on every verb except launchApp, so reaching a clearKeychain
        // seam verb here would fail this test.
        val driver = RecordingDeviceGateway()
        val result = run(driver, MaestroCommand(launchAppCommand = LaunchAppCommand(appId = "com.example.app", clearKeychain = true)))

        assertThat(result.success).isTrue()
        assertThat(driver.launched).containsExactly("com.example.app")
    }

    @Test
    fun `launchApp with clearKeychain on iOS still walls with the runner's message`() {
        val driver = RecordingDeviceGateway()
        val orchestra = Orchestra(driver = driver, platform = Platform.IOS)
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            runBlocking {
                orchestra.runFlow(listOf(MaestroCommand(launchAppCommand = LaunchAppCommand(appId = "com.example.app", clearKeychain = true))))
            }
        }
        assertThat(e.message).isEqualTo("launchApp modifier clearKeychain")
        assertThat(driver.launched).isEmpty()
    }

    // --- Repoint 2: selector tap ---

    @Test
    fun `tapOn by id routes the translated selector through the driver`() {
        val driver = RecordingDeviceGateway()
        val result = run(driver, MaestroCommand(tapOnElement = TapOnElementCommand(selector = ElementSelector(idRegex = "fabAddIcon"))))

        assertThat(result.success).isTrue()
        assertThat(driver.tapped).hasSize(1)
        assertThat(driver.tappedSelectors).containsExactly(Selector.Id("fabAddIcon"))
    }

    @Test
    fun `tapOn with longPress routes to the driver's longPress verb, not tap`() {
        // device-core serves Locator.longPress now; the modifier guard retired. A longPress tapOn
        // routes to driver.longPress (held press), never driver.tap.
        val driver = RecordingDeviceGateway()
        val result = run(driver, MaestroCommand(tapOnElement = TapOnElementCommand(selector = ElementSelector(idRegex = "x"), longPress = true)))
        assertThat(result.success).isTrue()
        assertThat(driver.longPressed.map { it.idRegex }).containsExactly("x")
        assertThat(driver.tapped).isEmpty()
    }

    @Test
    fun `tapOn with repeat throws NotImplemented with the runner's message`() {
        val driver = RecordingDeviceGateway()
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            run(driver, MaestroCommand(tapOnElement = TapOnElementCommand(selector = ElementSelector(idRegex = "x"), repeat = TapRepeat(repeat = 2, delay = 0))))
        }
        assertThat(e.message).isEqualTo("tapOnElement modifier repeat")
        assertThat(driver.tapped).isEmpty()
    }

    // --- Repoint 3: assertVisibility ---

    @Test
    fun `assertVisible routes through the driver with VISIBLE mode on a passing verdict`() {
        val driver = RecordingDeviceGateway()
        val result = run(driver, MaestroCommand(assertConditionCommand = AssertConditionCommand(Condition(visible = ElementSelector(textRegex = "Form Test")))))

        assertThat(result.success).isTrue()
        assertThat(driver.asserted).hasSize(1)
        assertThat(driver.asserted.single().second).isEqualTo(AssertMode.VISIBLE)
        assertThat(driver.assertedSelectors).containsExactly(Selector.Text("Form Test", dev.mobile.devicecore.prototype.api.Match.PATTERN, ignoreCase = true))
    }

    @Test
    fun `assertNotVisible routes through the driver with NOT_VISIBLE mode on a passing verdict`() {
        val driver = RecordingDeviceGateway()
        val result = run(driver, MaestroCommand(assertConditionCommand = AssertConditionCommand(Condition(notVisible = ElementSelector(textRegex = "kwyjibo")))))

        assertThat(result.success).isTrue()
        assertThat(driver.asserted.single().second).isEqualTo(AssertMode.NOT_VISIBLE)
    }

    @Test
    fun `assertVisible with an explicit timeout threads the deadline to the driver`() {
        // extendedWaitUntil / assertVisible with `timeout:` is a WAITED verb; the explicit deadline
        // is threaded into the seam VERBATIM. No interaction discount (removed — see Orchestra's
        // evaluateCondition note), so it's exactly the value the flow gave, never a shaved one.
        val driver = RecordingDeviceGateway()
        val result = run(driver, MaestroCommand(assertConditionCommand = AssertConditionCommand(
            Condition(visible = ElementSelector(textRegex = "Form Test")), timeout = "5000")))
        assertThat(result.success).isTrue()
        assertThat(driver.assertedTimeouts).containsExactly(5_000L)
    }

    @Test
    fun `assertNotVisible routes NOT_VISIBLE onto the seam (NotImplemented over the real driver)`() {
        // Over the RECORDING fake, NOT_VISIBLE still routes and records — routing is intact.
        // Over the REAL driver the seam throws NotImplemented; the real-driver group tests cover that.
        val driver = RecordingDeviceGateway()
        val result = run(driver, MaestroCommand(assertConditionCommand = AssertConditionCommand(
            Condition(notVisible = ElementSelector(textRegex = "kwyjibo")))))
        assertThat(result.success).isTrue()
        assertThat(driver.asserted.single().second).isEqualTo(AssertMode.NOT_VISIBLE)
    }

    @Test
    fun `assertVisible surfaces a failing verdict as an AssertionFailure`() {
        // Driver's own false verdict throws AssertionFailure; the assert command must fail the flow.
        val driver = RecordingDeviceGateway(onAssert = { selector, _ ->
            throw MaestroException.AssertionFailure(
                message = "Assertion is false: ${selector.description()} is visible",
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
        val driver = RecordingDeviceGateway()
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
        val driver = RecordingDeviceGateway()
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

    @Test
    fun `a visibility when guard asks point-in-time (0L) while an explicit assert threads its full deadline`() {
        // Guards must stay single-shot: a `when: visible:` guard passes 0L so an absent-element guard
        // never blocks the full lookupTimeoutMs (~17s). Only the explicit assertVisible waits — here
        // the default lookupTimeoutMs, 17000L. A passing verdict (fake never throws) proceeds.
        val driver = RecordingDeviceGateway()
        val leaf = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val ran = mutableListOf<String>()
        val guarded = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(leaf),
                condition = Condition(visible = ElementSelector(textRegex = "Guarded")),
                config = null,
            ),
        )
        val explicitAssert = MaestroCommand(
            assertConditionCommand = AssertConditionCommand(Condition(visible = ElementSelector(textRegex = "Explicit"))),
        )
        val orchestra = Orchestra(
            driver = driver,
            platform = Platform.ANDROID,
            onCommandComplete = { _, cmd -> if (cmd == leaf) ran += "ran" },
        )

        val result = runBlocking { orchestra.runFlow(listOf(guarded, explicitAssert)) }

        assertThat(result.success).isTrue()
        assertThat(ran).containsExactly("ran") // passing guard verdict -> subflow proceeds
        // ONE deadline for guards and asserts (LOOKUP_TIMEOUT_DERIVATION.md), threaded FULL — no
        // interaction discount. Both the guard and the explicit assert get exactly lookupTimeoutMs
        // (12s). The old discount could drain this to ~0 after a time-consuming prior command, and a
        // 0-budget waitFor is structurally always-false on the Android seam — the bug the fidelity
        // harness caught on Vaulty 001. See `guard budget survives a slow prior command` below.
        assertThat(driver.assertedTimeouts).containsExactly(12_000L, 12_000L).inOrder()
    }

    @Test
    fun `an absent visibility when guard skips the body and threads the single lookup deadline`() {
        // A false verdict on the guard (fake throws AssertionFailure) -> evaluateCondition returns
        // false -> runFlow skips its body. The guard asks with the SAME single lookupTimeoutMs as
        // asserts, discounted by adjustedToLatestInteraction (LOOKUP_TIMEOUT_DERIVATION.md) — the
        // old point-in-time 0L was structurally always-false on the Android seam (waitFor needs
        // ~300ms of agreeing reads), which the fidelity harness caught on Vaulty 001/010/031. The
        // seam owns honoring the deadline; the fake answers instantly, so nothing here blocks.
        val driver = RecordingDeviceGateway(onAssert = { selector, _ ->
            throw MaestroException.AssertionFailure(
                message = "Assertion is false: ${selector.description()} is visible",
                debugMessage = "fake driver false verdict",
            )
        })
        val leaf = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val ran = mutableListOf<String>()
        val guarded = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(leaf),
                condition = Condition(visible = ElementSelector(textRegex = "Absent")),
                config = null,
            ),
        )
        val orchestra = Orchestra(
            driver = driver,
            platform = Platform.ANDROID,
            onCommandComplete = { _, cmd -> if (cmd == leaf) ran += "ran" },
        )

        val result = runBlocking { orchestra.runFlow(listOf(guarded)) }

        assertThat(result.success).isTrue()
        assertThat(ran).isEmpty() // absent guard verdict -> body skipped
        // Full budget, undiscounted — exactly lookupTimeoutMs.
        assertThat(driver.assertedTimeouts).containsExactly(12_000L)
    }

    @Test
    fun `a guard's budget survives a slow prior command - no discount (regression, Vaulty 001)`() {
        // THE 001 REGRESSION PIN. The old adjustedToLatestInteraction discount subtracted wall-clock
        // elapsed since the last *mutating* command. A preceding lookup that spent real time without
        // being mutating (a failed optional tap that waited its full budget) drained the next guard's
        // budget toward 0 — and a 0-budget waitFor is structurally always-false on the Android seam,
        // so the guard skipped a subflow whose element was plainly on screen (Vaulty 001, the backup
        // screen). With the discount removed the guard ALWAYS gets the full lookupTimeoutMs, whatever
        // ran before it. Here a delaying command sits before the guard; the guard's threaded timeout
        // must still be the full 12s, not a drained value.
        val driver = RecordingDeviceGateway()
        val guard = MaestroCommand(runFlowCommand = RunFlowCommand(
            commands = listOf(MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))),
            condition = Condition(visible = ElementSelector(idRegex = "target")),
            config = null,
        ))
        // A command that consumes wall-clock before the guard (JS sleep ~200ms) and is not a
        // screen-mutating interaction — the shape that used to poison the discount.
        val delay = MaestroCommand(evalScriptCommand = EvalScriptCommand(
            "var t = java.lang.System.currentTimeMillis(); while (java.lang.System.currentTimeMillis() - t < 200) {}"))
        runBlocking { Orchestra(driver = driver, platform = Platform.ANDROID).runFlow(listOf(delay, guard)) }

        // Exactly the full budget — the elapsed 200ms is NOT subtracted.
        assertThat(driver.assertedTimeouts).containsExactly(12_000L)
    }

    // --- W1.5: the REMAINING roadmap verbs route onto the seam ---
    //
    // Every non-element device op now goes through the driver, not `maestro.*`. Over the REAL driver
    // each roadmap verb throws NotImplemented naming the verb, so a NotImplemented whose message names
    // the expected verb is proof both of routing AND of the intended coverage-map throw. One
    // representative per commit group (A text/keyboard, B gestures, C screenshot, D app/device) plus
    // the failure-payload re-expression is enough — the mechanical rest share the same shape.

    /** Orchestra over the REAL driver; its roadmap verbs throw NotImplemented naming the verb. */
    private fun realOrchestra(artifactsDir: Path? = null): Orchestra = Orchestra(
        driver = RealDeviceGateway(),
        platform = Platform.ANDROID,
        artifactsDir = artifactsDir,
    )

    private fun runReal(orchestra: Orchestra, vararg commands: MaestroCommand) =
        runBlocking { orchestra.runFlow(commands.toList()) }

    @Test
    fun `group A - inputText routes onto the seam's inputText verb`() {
        // device-core serves inputText (focused-node SET_TEXT) now; it routes to the driver, no wall.
        val driver = RecordingDeviceGateway()
        val result = run(driver, MaestroCommand(inputTextCommand = InputTextCommand(text = "hello")))
        assertThat(result.success).isTrue()
        assertThat(driver.inputTexts).containsExactly("hello")
    }

    @Test
    fun `group B - direction swipe routes onto the seam`() {
        // device-core serves a targetless directional swipe now; it routes to the driver, no wall.
        val driver = RecordingDeviceGateway()
        val result = run(driver, MaestroCommand(swipeCommand = SwipeCommand(direction = SwipeDirection.UP)))
        assertThat(result.success).isTrue()
        assertThat(driver.swipes).containsExactly(SwipeDirection.UP)
    }

    @Test
    fun `group C - non-crop takeScreenshot routes onto the seam and throws NotImplemented`(@TempDir artifactsDir: Path) {
        // artifactsDir keeps the pre-throw output sink inside a temp dir instead of the repo.
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            runReal(realOrchestra(artifactsDir), MaestroCommand(takeScreenshotCommand = TakeScreenshotCommand(path = "shot")))
        }
        assertThat(e.message).contains("takeScreenshot")
    }

    @Test
    fun `group D - setLocation routes onto the seam and throws NotImplemented`() {
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            runReal(realOrchestra(), MaestroCommand(setLocationCommand = SetLocationCommand(latitude = "1.0", longitude = "2.0")))
        }
        assertThat(e.message).contains("setLocation")
    }

    @Test
    fun `assertNotVisible over the real driver throws NotImplemented naming the GONE verb`() {
        val e = assertThrows(MaestroException.NotImplemented::class.java) {
            runReal(realOrchestra(), MaestroCommand(assertConditionCommand = AssertConditionCommand(
                Condition(notVisible = ElementSelector(textRegex = "kwyjibo")))))
        }
        assertThat(e.message).contains("assertNotVisible")
    }

    @Test
    fun `group C - assertion failure payload is built without a device hierarchy read`() {
        // The ~9 failure-payload sites no longer read a device hierarchy (the `maestro.TreeNode`
        // `hierarchyRoot` was dropped entirely in the device-core converge). A driver that booms on
        // ANY read proves the error is assembled with no device round-trip: an invalid
        // assertScreenshot threshold fails with AssertionFailure, and the recording driver is never
        // touched.
        val driver = RecordingDeviceGateway()
        assertThrows(MaestroException.AssertionFailure::class.java) {
            run(driver, MaestroCommand(assertScreenshotCommand = AssertScreenshotCommand(path = "ref.png", thresholdPercentage = "not-a-number")))
        }
    }
}
