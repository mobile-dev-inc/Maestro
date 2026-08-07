package maestro.orchestra.backend

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.Bounds
import maestro.DeviceInfo
import maestro.FindElementResult
import maestro.Maestro
import maestro.ScreenRecording
import maestro.ScrollDirection
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import okio.Sink
import maestro.device.Platform
import maestro.orchestra.AddMediaCommand
import maestro.orchestra.AirplaneValue
import maestro.orchestra.AssertDarkModeCommand
import maestro.orchestra.AssertLightModeCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.DarkModeValue
import maestro.orchestra.DefineVariablesCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.PasteTextCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.SetAirplaneModeCommand
import maestro.orchestra.SetDarkModeCommand
import maestro.orchestra.SetLocationCommand
import maestro.orchestra.SetOrientationCommand
import maestro.orchestra.SetPermissionsCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TapOnPointCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.ToggleAirplaneModeCommand
import maestro.orchestra.ToggleDarkModeCommand
import maestro.orchestra.TravelCommand
import maestro.orchestra.WaitForAnimationToEndCommand
import org.junit.jupiter.api.Test

/**
 * Router-level seam test: proves Orchestra.executeCommand's above/below split. Below-seam,
 * relocated commands (e.g. LaunchAppCommand, and the second passthrough batch: tapOnPoint,
 * tapOnPointV2, scroll, setPermissions, waitForAnimationToEnd, setLocation, setOrientation,
 * addMedia, setAirplaneMode, toggleAirplaneMode, setDarkMode, toggleDarkMode, assertDarkMode,
 * travel, plus Task 1.6's swipe, scrollUntilVisible, and assertLightMode, plus Task 1.7's
 * pasteText) must reach the [ExecutionBackend]; above-seam commands (flow control / variables,
 * e.g. DefineVariablesCommand) must never reach it — they stay entirely inside Orchestra.
 */
class RouterSeamTest {

    /** Records every command handed to [execute] so the test can assert real dispatch, not just a stub return. */
    private class RecordingBackend : ExecutionBackend {
        val executed = mutableListOf<Command>()

        override fun open(appId: String?) {}
        override fun close() {}

        override suspend fun execute(command: Command, context: BackendContext): CommandExecutionResult {
            executed.add(command)
            return CommandExecutionResult(mutating = true)
        }

        override fun viewHierarchy(excludeKeyboardElements: Boolean): ViewHierarchy = ViewHierarchy(TreeNode())

        override suspend fun findElement(
            selector: ElementSelector,
            optional: Boolean,
            timeoutMs: Long?,
            context: BackendContext,
        ): FindElementResult = error("findElement is not exercised by RouterSeamTest")

        override suspend fun evaluateCondition(
            condition: Condition?,
            commandOptional: Boolean,
            timeoutMs: Long?,
            context: BackendContext,
        ): Boolean = error("evaluateCondition is not exercised by RouterSeamTest")

        override val deviceInfo: DeviceInfo
            get() = DeviceInfo(
                platform = Platform.ANDROID,
                widthPixels = 1080,
                heightPixels = 1920,
                widthGrid = 1080,
                heightGrid = 1920,
            )

        override suspend fun takeScreenshot(out: Sink, compressed: Boolean, bounds: Bounds?) =
            error("takeScreenshot is not exercised by RouterSeamTest")

        override suspend fun startScreenRecording(out: Sink): ScreenRecording =
            error("startScreenRecording is not exercised by RouterSeamTest")

        override fun setAndroidChromeDevToolsEnabled(enabled: Boolean) =
            error("setAndroidChromeDevToolsEnabled is not exercised by RouterSeamTest")
    }

    @Test
    fun `relocated commands reach the backend, above-seam commands never do`() {
        val recordingBackend = RecordingBackend()
        val fakeMaestro: Maestro = mockk(relaxed = true)
        val orchestra = Orchestra(
            maestro = fakeMaestro,
            backend = recordingBackend,
        )

        val launchApp = LaunchAppCommand(appId = "com.example.app")
        val scroll = ScrollCommand()
        val tapOnPoint = TapOnPointCommand(x = 10, y = 20)
        val tapOnPointV2 = TapOnPointV2Command(point = "10,20")
        val setPermissions = SetPermissionsCommand(appId = "com.example.app", permissions = mapOf("all" to "allow"))
        val waitForAnimationToEnd = WaitForAnimationToEndCommand(timeout = "1000")
        val setLocation = SetLocationCommand(latitude = "12.34", longitude = "56.78")
        val setOrientation = SetOrientationCommand(orientation = "PORTRAIT")
        val addMedia = AddMediaCommand(mediaPaths = listOf("/tmp/media.png"))
        val setAirplaneMode = SetAirplaneModeCommand(value = AirplaneValue.Enable)
        val toggleAirplaneMode = ToggleAirplaneModeCommand()
        val setDarkMode = SetDarkModeCommand(value = DarkModeValue.Enable)
        val toggleDarkMode = ToggleDarkModeCommand()
        val assertDarkMode = AssertDarkModeCommand()
        val assertLightMode = AssertLightModeCommand()
        val travel = TravelCommand(points = listOf(TravelCommand.GeoPoint(latitude = "12.34", longitude = "56.78")))
        val swipe = SwipeCommand(direction = SwipeDirection.UP)
        val scrollUntilVisible = ScrollUntilVisibleCommand(
            selector = ElementSelector(textRegex = "Login"),
            direction = ScrollDirection.DOWN,
            visibilityPercentage = 100,
            centerElement = false,
        )
        val defineVariables = DefineVariablesCommand(env = mapOf("X" to "y"))
        // Task 1.7: pasteText now routes to the backend (reads context.copiedText).
        val pasteText = PasteTextCommand()

        val relocated = listOf(
            launchApp, scroll, tapOnPoint, tapOnPointV2, setPermissions, waitForAnimationToEnd,
            setLocation, setOrientation, addMedia, setAirplaneMode, toggleAirplaneMode,
            setDarkMode, toggleDarkMode, assertDarkMode, assertLightMode, travel, swipe, pasteText,
        )

        val flow = (relocated + scrollUntilVisible + defineVariables).map { MaestroCommand(it) }

        runBlocking { orchestra.runFlow(flow) }

        // Below-seam + relocated: reaches the backend.
        relocated.forEach { command ->
            assertThat(recordingBackend.executed).contains(command)
        }
        // ScrollUntilVisibleCommand.evaluateScripts unconditionally reinterprets scrollDuration as
        // a 0-100 "speed" and rewrites it to a millisecond duration (pre-existing behavior,
        // unrelated to this relocation) — so the object Orchestra hands the backend isn't
        // reference-equal to the one built above. Match on the fields that pass through
        // evaluateScripts unchanged instead of full equality.
        val routedScroll = recordingBackend.executed.filterIsInstance<ScrollUntilVisibleCommand>().singleOrNull()
        assertThat(routedScroll).isNotNull()
        assertThat(routedScroll!!.selector).isEqualTo(scrollUntilVisible.selector)
        assertThat(routedScroll.direction).isEqualTo(scrollUntilVisible.direction)
        assertThat(routedScroll.visibilityPercentage).isEqualTo(scrollUntilVisible.visibilityPercentage)
        assertThat(routedScroll.centerElement).isEqualTo(scrollUntilVisible.centerElement)
        // Above-seam: flow control/variables never reach the backend.
        assertThat(recordingBackend.executed).doesNotContain(defineVariables)
    }
}
