package maestro.orchestra.backend

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import maestro.DeviceInfo
import maestro.Maestro
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.device.Platform
import maestro.orchestra.AddMediaCommand
import maestro.orchestra.AirplaneValue
import maestro.orchestra.AssertDarkModeCommand
import maestro.orchestra.Command
import maestro.orchestra.DarkModeValue
import maestro.orchestra.DefineVariablesCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.PasteTextCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.SetAirplaneModeCommand
import maestro.orchestra.SetDarkModeCommand
import maestro.orchestra.SetLocationCommand
import maestro.orchestra.SetOrientationCommand
import maestro.orchestra.SetPermissionsCommand
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
 * travel) must reach the [ExecutionBackend]; above-seam commands (flow control / variables, e.g.
 * DefineVariablesCommand) must never reach it — they stay entirely inside Orchestra. A
 * still-in-Orchestra below-seam command that hasn't been relocated yet (PasteTextCommand) is
 * included too, to show the backend only sees what's actually been routed to it, not everything
 * below the seam.
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

        override val deviceInfo: DeviceInfo
            get() = DeviceInfo(
                platform = Platform.ANDROID,
                widthPixels = 1080,
                heightPixels = 1920,
                widthGrid = 1080,
                heightGrid = 1920,
            )
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
        val travel = TravelCommand(points = listOf(TravelCommand.GeoPoint(latitude = "12.34", longitude = "56.78")))
        val defineVariables = DefineVariablesCommand(env = mapOf("X" to "y"))
        // Still-in-Orchestra: the below-seam command that must NOT reach the backend yet.
        val pasteText = PasteTextCommand()

        val relocated = listOf(
            launchApp, scroll, tapOnPoint, tapOnPointV2, setPermissions, waitForAnimationToEnd,
            setLocation, setOrientation, addMedia, setAirplaneMode, toggleAirplaneMode,
            setDarkMode, toggleDarkMode, assertDarkMode, travel,
        )

        val flow = (relocated + defineVariables + pasteText).map { MaestroCommand(it) }

        runBlocking { orchestra.runFlow(flow) }

        // Below-seam + relocated: reaches the backend.
        relocated.forEach { command ->
            assertThat(recordingBackend.executed).contains(command)
        }
        // Above-seam: flow control/variables never reach the backend.
        assertThat(recordingBackend.executed).doesNotContain(defineVariables)
        // Below-seam but not yet relocated: also doesn't reach the backend (still handled by
        // Orchestra directly) — the backend only sees what's actually been routed to it.
        assertThat(recordingBackend.executed).doesNotContain(pasteText)
    }
}
