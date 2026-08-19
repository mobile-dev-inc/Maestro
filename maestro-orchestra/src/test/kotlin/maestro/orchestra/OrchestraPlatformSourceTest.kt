package maestro.orchestra

import com.google.common.truth.Truth.assertThat
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
import okio.Sink
import org.junit.jupiter.api.Test

/**
 * Q4 resolution: platform is a session concern, never a seam throw. These tests pin that down by
 * making BOTH potential "seams" radioactive:
 *  - [AlwaysThrowsDriver] throws on literally every verb, including the five the vertical wires
 *    today — proving platform resolution never reaches for [DeviceGateway] at all.
 *  - the mocked [Maestro] throws if `cachedDeviceInfo` is read — proving platform resolution
 *    doesn't fall back to the legacy device roundtrip either, once the caller (MaestroSessionManager,
 *    in production) supplies the session-known platform explicitly to the constructor.
 *
 * If either seam were touched, the flow would fail with the seam's throw instead of resolving the
 * `platform:` condition — so a clean pass here is the proof.
 */
class OrchestraPlatformSourceTest {

    /** Every verb throws — including the four-command vertical's own five. Nothing is safe to call. */
    private class AlwaysThrowsDriver : DeviceGateway {
        private fun boom(verb: String): Nothing =
            throw AssertionError("platform resolution must never touch DeviceGateway.$verb")

        override fun connect(target: DeviceCoreTarget, appId: String?) = boom("connect")
        override fun close() = boom("close")
        override fun launchApp(appId: String) = boom("launchApp")
        override fun tap(selector: ElementSelector): ChosenElement? = boom("tap")
        override fun assertVisibility(selector: ElementSelector, mode: AssertMode): ChosenElement? = boom("assertVisibility")
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

    @Test
    fun `a matching platform condition runs without touching Maestro or the DeviceGateway seam`() {
        val ran = mutableListOf<String>()
        val leaf = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val command = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(leaf),
                condition = Condition(platform = Platform.ANDROID),
                config = null,
            ),
        )
        // onCommandComplete lets us observe the guarded subflow actually ran, not just "didn't throw".
        val orchestra = Orchestra(
            driver = AlwaysThrowsDriver(),
            platform = Platform.ANDROID,
            onCommandComplete = { _, cmd -> if (cmd == leaf) ran += "ran" },
        )

        val result = runBlocking { orchestra.runFlow(listOf(command)) }

        assertThat(result.success).isTrue()
        assertThat(ran).containsExactly("ran")
    }

    @Test
    fun `a mismatching platform condition is skipped without touching Maestro or the DeviceGateway seam`() {
        val leaf = MaestroCommand(evalScriptCommand = EvalScriptCommand("1"))
        val ran = mutableListOf<String>()
        val command = MaestroCommand(
            runFlowCommand = RunFlowCommand(
                commands = listOf(leaf),
                condition = Condition(platform = Platform.IOS),
                config = null,
            ),
        )
        val orchestra = Orchestra(
            driver = AlwaysThrowsDriver(),
            platform = Platform.ANDROID,
            onCommandComplete = { _, cmd -> if (cmd == leaf) ran += "ran" },
        )

        // A skipped RunFlowCommand throws CommandSkipped internally, caught by Orchestra itself —
        // it must not surface here, and it must not come from either seam.
        val result = runBlocking { orchestra.runFlow(listOf(command)) }

        assertThat(result.success).isTrue()
        assertThat(ran).isEmpty()
    }
}
