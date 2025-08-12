package maestro.orchestra.yaml

import com.google.common.truth.Truth.assertThat
import maestro.DeviceOrientation
import maestro.KeyCode
import maestro.Point
import maestro.ScrollDirection
import maestro.SwipeDirection
import maestro.TapRepeat
import maestro.orchestra.AddMediaCommand
import maestro.orchestra.AirplaneValue
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearKeychainCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.CopyTextFromCommand
import maestro.orchestra.DefineVariablesCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.EvalScriptCommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputRandomCommand
import maestro.orchestra.InputRandomType
import maestro.orchestra.InputTextCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.MaestroOnFlowComplete
import maestro.orchestra.MaestroOnFlowStart
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PasteTextCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.RepeatCommand
import maestro.orchestra.RunFlowCommand
import maestro.orchestra.RunScriptCommand
import maestro.orchestra.SetOrientationCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.SetAirplaneModeCommand
import maestro.orchestra.SetLocationCommand
import maestro.orchestra.StartRecordingCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.StopRecordingCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.ToggleAirplaneModeCommand
import maestro.orchestra.TravelCommand
import maestro.orchestra.WaitForAnimationToEndCommand
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.yaml.junit.YamlCommandsExtension
import maestro.orchestra.yaml.junit.YamlFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.file.FileSystems
import java.nio.file.Paths

@Suppress("JUnitMalformedDeclaration")
@ExtendWith(YamlCommandsExtension::class)
internal class YamlCommandReaderTest {

    @Test
    fun launchApp(
        @YamlFile("002_launchApp.yaml") commands: List<Command>,
    ) {
        assertThat(commands).containsExactly(
                ApplyConfigurationCommand(MaestroConfig(
                    appId = "com.example.app"
                )),
                LaunchAppCommand(
                    appId = "com.example.app"
                ),
        )
    }

    @Test
    fun launchApp_withClearState(
        @YamlFile("003_launchApp_withClearState.yaml") commands: List<Command>
    ) {
        assertThat(commands).containsExactly(
            ApplyConfigurationCommand(MaestroConfig(
                appId = "com.example.app",
            )),
            LaunchAppCommand(
                appId = "com.example.app",
                clearState = true,
            ),
        )
    }

    @Test
    fun config_unknownKeys(
        @YamlFile("008_config_unknownKeys.yaml") commands: List<Command>,
    ) {
        assertThat(commands).containsExactly(
            ApplyConfigurationCommand(MaestroConfig(
                appId = "com.example.app",
                ext = mapOf(
                    "extra" to true,
                    "extraMap" to mapOf(
                        "keyA" to "valueB"
                    ),
                    "extraArray" to listOf("itemA")
                )
            )),
            LaunchAppCommand(
                appId = "com.example.app",
            ),
        )
    }

    @Test
    fun launchApp_otherPackage(
        @YamlFile("017_launchApp_otherPackage.yaml") commands: List<Command>,
    ) {
        assertThat(commands).containsExactly(
            ApplyConfigurationCommand(MaestroConfig(
                appId = "com.example.app",
            )),
            LaunchAppCommand(
                appId = "com.other.app"
            ),
        )
    }

    @Test
    fun backPress_string(
        @YamlFile("018_backPress_string.yaml") commands: List<Command>,
    ) {
        assertThat(commands).containsExactly(
            ApplyConfigurationCommand(MaestroConfig(
                appId = "com.example.app",
            )),
            BackPressCommand(),
        )
    }

    @Test
    fun scroll_string(
        @YamlFile("019_scroll_string.yaml") commands: List<Command>,
    ) {
        assertThat(commands).containsExactly(
            ApplyConfigurationCommand(MaestroConfig(
                appId = "com.example.app",
            )),
            ScrollCommand(),
        )
    }

    @Test
    fun config_name(
        @YamlFile("020_config_name.yaml") commands: List<Command>,
    ) {
        assertThat(commands).containsExactly(
            ApplyConfigurationCommand(MaestroConfig(
                appId = "com.example.app",
                name = "Example Flow"
            )),
            LaunchAppCommand(
                appId = "com.example.app"
            ),
        )
    }

    // Misc. tests

    @Test
    fun readFromZip() {
        val resource = this::class.java.getResource("/YamlCommandReaderTest/flow.zip")!!.toURI()
        assertThat(resource.scheme).isEqualTo("file")

        val commands = FileSystems.newFileSystem(Paths.get(resource), null as ClassLoader?).use { fs ->
            YamlCommandReader.readCommands(fs.getPath("flow.yaml"))
        }

        assertThat(commands).isEqualTo(commands(
            ApplyConfigurationCommand(
                config = MaestroConfig(
                    appId = "com.example.app"
                )
            ),
            LaunchAppCommand(
                appId = "com.example.app"
            )
        ))
    }

    @Test
    fun onFlowStartCompleteHooks(
        @YamlFile("022_on_flow_start_complete.yaml") commands: List<Command>,
    ) {
        assertThat(commands).containsExactly(
            ApplyConfigurationCommand(
                config = MaestroConfig(
                    appId = "com.example.app",
                    onFlowStart = MaestroOnFlowStart(
                        commands = commands(
                            BackPressCommand()
                        )
                    ),
                    onFlowComplete = MaestroOnFlowComplete(
                        commands = commands(
                            ScrollCommand()
                        )
                    )
                )
            ),
            LaunchAppCommand(
                appId = "com.example.app"
            )
        )
    }

    @Test
    fun labels(
        @YamlFile("023_labels.yaml") commands: List<Command>,
    ) {
        assertThat(commands).containsExactly(
            ApplyConfigurationCommand(
                config=MaestroConfig(
                    appId="com.example.app"
                )
            ),

            // Taps
            TapOnElementCommand(
                selector = ElementSelector(idRegex = "foo"),
                retryIfNoChange = false,
                waitUntilVisible = false,
                longPress = false,
                label = "Tap on the important button"
            ),
            TapOnElementCommand(
                selector = ElementSelector(idRegex = "foo"),
                retryIfNoChange = false,
                waitUntilVisible = false,
                longPress = false,
                repeat = TapRepeat(
                    repeat = 2,
                    delay = 100L
                ),
                label = "Tap on the important button twice"
            ),
            TapOnElementCommand(
                selector = ElementSelector(idRegex = "foo"),
                retryIfNoChange = false,
                waitUntilVisible = false,
                longPress = true,
                label = "Press and hold the important button"
            ),
            TapOnPointV2Command(
                point = "50%,50%",
                retryIfNoChange = false,
                longPress = false,
                label = "Tap on the middle of the screen"
            ),

            //Assertions
            AssertConditionCommand(
                condition = Condition(visible = ElementSelector(idRegex = "bar")),
                label = "Check that the important number is visible"
            ),
            AssertConditionCommand(
                condition = Condition(notVisible = ElementSelector(idRegex = "bar2")),
                label = "Check that the secret number is invisible"
            ),
            AssertConditionCommand(
                condition = Condition(
                    scriptCondition = "\${5 == 5}"
                ),
                label = "Check that five is still what we think it is"
            ),


            // Inputs
            InputTextCommand(
                text = "correct horse battery staple",
                label = "Enter my secret password"
            ),
            InputRandomCommand(
                inputType = InputRandomType.TEXT_EMAIL_ADDRESS,
                label = "Enter a random email address"
            ),
            InputRandomCommand(
                inputType = InputRandomType.TEXT_PERSON_NAME,
                length = 8,
                label = "Enter a random person's name"
            ),
            InputRandomCommand(
                inputType = InputRandomType.NUMBER,
                length = 5,
                label = "Enter a random number"
            ),
            InputRandomCommand(
                inputType = InputRandomType.TEXT,
                length = 20,
                label = "Enter a random string"
            ),
            PressKeyCommand(
                code = KeyCode.ENTER,
                label = "Press the enter key"
            ),

            // Other
            BackPressCommand(
                label = "Go back to the previous screen"
            ),
            ClearKeychainCommand(
                label = "Clear the keychain"
            ),
            ClearStateCommand(
                appId = "com.example.app",
                label = "Wipe the app state"
            ),
            CopyTextFromCommand(
                selector = ElementSelector(idRegex = "foo"),
                label = "Copy the important text"
            ),
            EraseTextCommand(
                charactersToErase = 5,
                label = "Erase the last 5 characters"
            ),
            AssertConditionCommand(
                condition = Condition(visible = ElementSelector(textRegex="Some important text")),
                timeout = "1000",
                label = "Wait until the important text is visible"
            ),
            EvalScriptCommand(
                scriptString = "return 5;",
                label = "Get the number 5"
            ),
            HideKeyboardCommand(
                label = "Hide the keyboard"
            ),
            LaunchAppCommand(
                appId = "com.some.other",
                clearState = true,
                label = "Launch some other app"
            ),
            OpenLinkCommand(
                link = "https://www.example.com",
                autoVerify = false,
                browser = false,
                label = "Open the example website"
            ),
            PasteTextCommand(
                label = "Paste the important text"
            ),
            RunFlowCommand(
                config = null,
                commands = commands(
                    AssertConditionCommand(
                        condition = Condition(scriptCondition = "\${5 == 5}")
                    )
                ),
                label = "Check that five is still what we think it is"
            ),
            RunScriptCommand(
                script = "const myNumber = 1 + 1;",
                condition = null,
                sourceDescription = "023_runScript_test.js",
                label = "Run some special calculations"
            ),
            SetOrientationCommand(
                orientation = DeviceOrientation.LANDSCAPE_LEFT,
                label = "Set the device orientation"
            ),
            ScrollCommand(
                label = "Scroll down"
            ),
            ScrollUntilVisibleCommand(
                selector = ElementSelector(textRegex = "Footer"),
                direction = ScrollDirection.DOWN,
                timeout = "20000",
                scrollDuration = "40",
                visibilityPercentage = 100,
                label = "Scroll to the bottom",
                centerElement = false
            ),
            SetLocationCommand(
                latitude = "12.5266",
                longitude = "78.2150",
                label = "Set Location to Test Laboratory"
            ),
            StartRecordingCommand(
                path = "recording.mp4",
                label = "Start recording a video"
            ),
            StopAppCommand(
                appId = "com.some.other",
                label = "Stop that other app from running"
            ),
            StopRecordingCommand(
                label = "Stop recording the video"
            ),
            TakeScreenshotCommand(
                path = "baz",
                label = "Snap this for later evaluation"
            ),
            TravelCommand(
                points = listOf(
                    TravelCommand.GeoPoint("0.0","0.0"),
                    TravelCommand.GeoPoint("0.1","0.0"),
                    TravelCommand.GeoPoint("0.1","0.1"),
                    TravelCommand.GeoPoint("0.0","0.1"),
                ),
                speedMPS = 2000.0,
                label = "Run around the north pole"
            ),
            WaitForAnimationToEndCommand(
                timeout = 4000,
                label = "Wait for the thing to stop spinning"
            ),
            SwipeCommand(
                direction = SwipeDirection.DOWN,
                label = "Swipe down a bit"
            ),
            AddMediaCommand(
                mediaPaths = listOf(Paths.get("build/resources/test/YamlCommandReaderTest/023_image.png").toAbsolutePath().toString()),
                label = "Add a picture to the device"
            ),
            SetAirplaneModeCommand(
                value = AirplaneValue.Enable,
                label = "Turn on airplane mode for testing"
            ),
            ToggleAirplaneModeCommand(
                label = "Toggle airplane mode for testing"
            ),
            RepeatCommand(
                condition = Condition(visible = ElementSelector(textRegex = "Some important text")),
                commands = listOf(
                    MaestroCommand(
                        command = TapOnElementCommand(
                            selector = ElementSelector(idRegex = "foo"),
                            retryIfNoChange = false,
                            waitUntilVisible = false,
                            longPress = false,
                            label = "Tap on the important button"
                        )
                    ),
                    MaestroCommand(
                        command = TapOnElementCommand(
                            selector = ElementSelector(idRegex = "bar"),
                            retryIfNoChange = false,
                            waitUntilVisible = false,
                            longPress = false,
                            label = "Tap on the other important button"
                        )
                    )
                ),
                label = "Tap the 2 buttons until the text goes away"
            ),
        )
    }

    @Test
    fun commands_with_string_non_string(@YamlFile("024_string_non_string_commands.yaml") commands: List<Command>,) {
        assertThat(commands).containsExactly(
            ApplyConfigurationCommand(
                config= MaestroConfig(appId= "com.example.app")
            ),
            InputTextCommand(text = "correct horse battery staple"),
            InputTextCommand(text = "correct horse battery staple"),
            InputTextCommand(text = "4"),
            InputTextCommand(text = "false"),
            InputTextCommand(text = "1683113805263"),
            InputTextCommand(text = "4.12"),
            AssertConditionCommand(
                condition = Condition(
                    scriptCondition = "true"
                )
            ),
            AssertConditionCommand(
                condition = Condition(
                    scriptCondition = "323"
                )
            ),
            EvalScriptCommand(
                scriptString = "true"
            ),
            EvalScriptCommand(
                scriptString = "2 + 1"
            ),
            EvalScriptCommand(
                scriptString = "2"
            ),
            EvalScriptCommand(
                scriptString = "false == false"
            ),
            TapOnElementCommand(
                ElementSelector(
                    textRegex = "Hello",
                ),
                retryIfNoChange = false,
                waitUntilVisible = false,
                longPress = false
            ),
            TapOnElementCommand(
                selector = ElementSelector(textRegex = "Hello"),
                repeat = TapRepeat(2, TapOnElementCommand.DEFAULT_REPEAT_DELAY),
                retryIfNoChange = false,
                waitUntilVisible = false,
                longPress = false
            ),
            TapOnElementCommand(
                selector = ElementSelector(textRegex = "Hello"),
                longPress = true,
                retryIfNoChange = false,
                waitUntilVisible = false
            ),
            AssertConditionCommand(
                condition = Condition(
                    visible = ElementSelector(textRegex = "Hello"),
                ),
            ),
            CopyTextFromCommand(ElementSelector(textRegex = "Hello")),
            BackPressCommand(),
            BackPressCommand(),
            HideKeyboardCommand(),
            HideKeyboardCommand(),
            ScrollCommand(),
            ScrollCommand(),
            ClearKeychainCommand(),
            ClearKeychainCommand(),
            PasteTextCommand(),
            PasteTextCommand(),
        )
    }

    @Test
    fun killApp(
        @YamlFile("025_killApp.yaml") commands: List<Command>,
    ) {
        assertThat(commands).containsExactly(
            ApplyConfigurationCommand(MaestroConfig(
                appId = "com.example.app"
            )),
            KillAppCommand(
                appId = "com.example.app"
            ),
        )
    }

    @Test
    fun waitToSettleTimeoutMsCommands(
        @YamlFile("027_waitToSettleTimeoutMs.yaml") commands: List<Command>
    ) {
        assertThat(commands).containsExactly(
            ApplyConfigurationCommand(MaestroConfig(
                appId = "com.example.app"
            )),
            ScrollUntilVisibleCommand(
                selector = ElementSelector(idRegex = "maybe-later"),
                direction = ScrollDirection.DOWN,
                waitToSettleTimeoutMs = 50,
                centerElement = false,
                visibilityPercentage = 100
            ),
            SwipeCommand(
                startRelative = "90%, 50%",
                endRelative = "10%, 50%",
                waitToSettleTimeoutMs = 50
            ),
            SwipeCommand(
                direction = SwipeDirection.LEFT,
                duration = 400L,
                waitToSettleTimeoutMs = 50
            ),
            SwipeCommand(
                direction = SwipeDirection.LEFT,
                duration = 400L,
                elementSelector = ElementSelector(idRegex = "feeditem_identifier"),
                waitToSettleTimeoutMs = 50,
            ),
            SwipeCommand(
                startPoint = Point(x = 100, y = 200),
                endPoint = Point(x = 300, y = 400),
                waitToSettleTimeoutMs = 50,
                duration = 400L
            )
        )
    }

    private fun commands(vararg commands: Command): List<MaestroCommand> =
        commands.map(::MaestroCommand).toList()
}
