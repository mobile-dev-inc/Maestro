package maestro.orchestra.yaml

import com.google.common.truth.Truth.assertThat
import java.nio.file.FileSystems
import java.nio.file.Paths
import maestro.KeyCode
import maestro.ScrollDirection
import maestro.TapRepeat
import maestro.orchestra.*
import maestro.orchestra.error.InvalidInitFlowFile
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.yaml.junit.YamlCommandsExtension
import maestro.orchestra.yaml.junit.YamlExceptionExtension
import maestro.orchestra.yaml.junit.YamlFile
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@Suppress("JUnitMalformedDeclaration")
@ExtendWith(YamlCommandsExtension::class, YamlExceptionExtension::class)
internal class YamlCommandReaderTest {

    @Test
    fun empty(
        @YamlFile("001_empty.yaml") e: SyntaxError,
    ) {
        assertThat(e.message).contains("Flow files must contain a config section and a commands section")
    }

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
    fun config_empty(
        @YamlFile("004_config_empty.yaml") e: SyntaxError,
    ) {
        assertThat(e.message).contains("Flow files must contain a config section and a commands section")
    }

    @Test
    fun config_noAppId(
        @YamlFile("005_config_noAppId.yaml") e: SyntaxError,
    ) {
        assertThat(e.message).contains("appId due to missing (therefore NULL) value for creator parameter appId which is a non-nullable type")
    }

    @Test
    fun emptyCommands(
        @YamlFile("006_emptyCommands.yaml") e: SyntaxError,
    ) {
        assertThat(e.message).contains("Flow files must contain a config section and a commands section")
    }

    @Test
    fun initFlow(
        @YamlFile("007_initFlow.yaml") e: SyntaxError,
    ) {
        assertThat(e.message).containsMatch("initFlow command used at.*is deprecated")
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
    fun invalidCommand(
        @YamlFile("009_invalidCommand.yaml") e: SyntaxError,
    ) {
        assertThat(e.message).contains("Unrecognized field \"invalid\"")
    }

    @Test
    fun invalidCommand_string(
        @YamlFile("010_invalidCommand_string.yaml") e: SyntaxError,
    ) {
        assertThat(e.message).contains("Invalid command: \"invalid\"")
    }

    @Test
    fun initFlow_file(
        @YamlFile("011_initFlow_file.yaml") e: SyntaxError,
    ) {
        assertThat(e.message).containsMatch("initFlow command used at.*is deprecated")
    }

    @Test
    fun initFlow_emptyString(
        @YamlFile("012_initFlow_emptyString.yaml") commands: List<Command>,
    ) {
        assertThat(commands).containsExactly(
            ApplyConfigurationCommand(MaestroConfig(
                appId = "com.example.app",
            )),
            LaunchAppCommand(
                appId = "com.example.app",
            ),
        )
    }

    @Test
    fun initFlow_invalidFile(
        @YamlFile("013_initFlow_invalidFile.yaml") e: SyntaxError,
    ) {
        assertThat(e.message).containsMatch("initFlow command used at.*is deprecated")
    }

    @Test
    fun initFlow_recursive(
        @YamlFile("014_initFlow_recursive.yaml") e: SyntaxError,
    ) {
        assertThat(e.message).containsMatch("initFlow command used at.*is deprecated")
    }

    @Test
    fun onlyCommands(
        @YamlFile("015_onlyCommands.yaml") e: SyntaxError,
    ) {
        assertThat(e.message).contains("Flow files must contain a config section and a commands section")
    }

    @Test
    fun launchApp_emptyString(
        @YamlFile("016_launchApp_emptyString.yaml") e: SyntaxError,
    ) {
        assertThat(e.message).contains("No mapping provided for YamlFluentCommand")
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

        val commands = FileSystems.newFileSystem(Paths.get(resource), null).use { fs ->
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
    fun launchAppSyntaxError(
        @YamlFile("021_launchApp_syntaxError.yaml") e: SyntaxError,
    ) {
        assertThat(e.message).contains("Cannot deserialize value of type")
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
                retryIfNoChange = true,
                waitUntilVisible = false,
                longPress = false,
                label = "Tap on the important button"
            ),
            TapOnElementCommand(
                selector = ElementSelector(idRegex = "foo"),
                retryIfNoChange = true,
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
                retryIfNoChange = true,
                waitUntilVisible = false,
                longPress = true,
                label = "Press and hold the important button"
            ),
            TapOnPointV2Command(
                point = "50%,50%",
                retryIfNoChange = true,
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
            ScrollCommand(
                label = "Scroll down"
            ),
            ScrollUntilVisibleCommand(
                selector = ElementSelector(textRegex = "Footer"),
                direction = ScrollDirection.DOWN,
                timeout = 20000,
                scrollDuration = 601,
                visibilityPercentage = 100,
                label = "Scroll to the bottom"
            ),
            SetLocationCommand(
                latitude = 12.5266,
                longitude = 78.2150,
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
                    TravelCommand.GeoPoint(0.0,0.0),
                    TravelCommand.GeoPoint(0.1,0.0),
                    TravelCommand.GeoPoint(0.1,0.1),
                    TravelCommand.GeoPoint(0.0,0.1),
                ),
                speedMPS = 2000.0,
                label = "Run around the north pole"
            ),
            WaitForAnimationToEndCommand(
                timeout = 4000,
                label = "Wait for the thing to stop spinning"
            ),
            RepeatCommand(
                condition = Condition(visible = ElementSelector(textRegex = "Some important text")),
                commands = listOf(
                    MaestroCommand(
                        command = TapOnElementCommand(
                            selector = ElementSelector(idRegex = "foo"),
                            retryIfNoChange = true,
                            waitUntilVisible = false,
                            longPress = false,
                            label = "Tap on the important button"
                        )
                    ),
                    MaestroCommand(
                        command = TapOnElementCommand(
                            selector = ElementSelector(idRegex = "bar"),
                            retryIfNoChange = true,
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

    private fun commands(vararg commands: Command): List<MaestroCommand> =
        commands.map(::MaestroCommand).toList()
}
