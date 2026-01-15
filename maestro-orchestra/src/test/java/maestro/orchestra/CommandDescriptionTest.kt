package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import maestro.js.GraalJsEngine
import maestro.js.RhinoJsEngine
import maestro.orchestra.yaml.junit.YamlFile
import maestro.orchestra.yaml.junit.YamlCommandsExtension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(YamlCommandsExtension::class)
internal class CommandDescriptionTest {

    @Test
    fun `original description contains raw command details`(
        @YamlFile("029_command_descriptions.yaml") commands: List<Command>
    ) {
        val jsEngine = GraalJsEngine(platform = "ios")
        jsEngine.putEnv("username", "Alice")

        // Tap command with label
        val tapCommand = commands[1] as TapOnElementCommand
        assertThat(tapCommand.label).isEqualTo("Scroll to find the maybe-later button")
        assertThat(tapCommand.description()).isEqualTo("Scroll to find the maybe-later button")  
        assertThat(tapCommand.originalDescription).isEqualTo("Tap on \"maybe-later\"")

        // Input command without label
        val inputCommand = commands[2] as InputTextCommand
        val evaluatedInput = inputCommand.evaluateScripts(jsEngine)
        assertThat(evaluatedInput.label).isNull()
        assertThat(evaluatedInput.description()).isEqualTo("Input text Alice")
        assertThat(evaluatedInput.originalDescription).isEqualTo("Input text Alice")

        // Assert command without label
        val assertCommand = commands[3] as AssertConditionCommand
        val evaluatedAssert = assertCommand.evaluateScripts(jsEngine)
        assertThat(evaluatedAssert.label).isNull()
        assertThat(evaluatedAssert.description()).isEqualTo("Assert that \"Hello Alice\" is visible")
        assertThat(evaluatedAssert.originalDescription).isEqualTo("Assert that \"Hello Alice\" is visible")

        jsEngine.close()
    }

    @Test
    fun `description uses label when available`(
        @YamlFile("029_command_descriptions.yaml") commands: List<Command>
    ) {
        val jsEngine = GraalJsEngine(platform = "ios")
        jsEngine.putEnv("username", "Bob")

        // Tap command with label
        val tapCommand = commands[1] as TapOnElementCommand
        assertThat(tapCommand.label).isEqualTo("Scroll to find the maybe-later button")
        assertThat(tapCommand.description()).isEqualTo("Scroll to find the maybe-later button") 
        assertThat(tapCommand.originalDescription).isEqualTo("Tap on \"maybe-later\"")

        // Input command without label
        val inputCommand = commands[2] as InputTextCommand
        val evaluatedInput = inputCommand.evaluateScripts(jsEngine)
        assertThat(evaluatedInput.label).isNull()
        assertThat(evaluatedInput.description()).isEqualTo("Input text Bob")
        assertThat(evaluatedInput.originalDescription).isEqualTo("Input text Bob")

        jsEngine.close()
    }

    @Test
    fun `description evaluates script variables`(
        @YamlFile("029_command_descriptions.yaml") commands: List<Command>
    ) {
        val jsEngine = GraalJsEngine(platform = "ios")
        jsEngine.putEnv("username", "Charlie")

        // Tap command with label (should be unchanged by evaluation)
        val tapCommand = commands[1] as TapOnElementCommand
        assertThat(tapCommand.originalDescription).isEqualTo("Tap on \"maybe-later\"")
        val evaluatedTap = tapCommand.evaluateScripts(jsEngine)
        assertThat(evaluatedTap.label).isEqualTo("Scroll to find the maybe-later button")
        assertThat(evaluatedTap.description()).isEqualTo("Scroll to find the maybe-later button")
        assertThat(evaluatedTap.originalDescription).isEqualTo("Tap on \"maybe-later\"")

        // Input command with variable
        val inputCommand = commands[2] as InputTextCommand
        assertThat(inputCommand.originalDescription).isEqualTo("Input text \${username}")
        val evaluatedInput = inputCommand.evaluateScripts(jsEngine)
        assertThat(evaluatedInput.label).isNull()
        assertThat(evaluatedInput.description()).isEqualTo("Input text Charlie")
        assertThat(evaluatedInput.originalDescription).isEqualTo("Input text Charlie")

        // Assert command with variable
        val assertCommand = commands[3] as AssertConditionCommand
        assertThat(assertCommand.originalDescription).isEqualTo("Assert that \"Hello \${username}\" is visible")
        val evaluatedAssert = assertCommand.evaluateScripts(jsEngine)
        assertThat(evaluatedAssert.label).isNull()
        assertThat(evaluatedAssert.description()).isEqualTo("Assert that \"Hello Charlie\" is visible")
        assertThat(evaluatedAssert.originalDescription).isEqualTo("Assert that \"Hello Charlie\" is visible")

        jsEngine.close()
    }

    @Test
    fun `TapOnElementCommand description includes relativePoint when provided`() {
        // given
        val command = TapOnElementCommand(
            selector = ElementSelector(textRegex = "Submit"),
            relativePoint = "50%, 90%",
            label = "Tap Submit Button"
        )

        // when & then
        assertThat(command.originalDescription).isEqualTo("Tap on \"Submit\" at 50%, 90%")
        assertThat(command.description()).isEqualTo("Tap Submit Button")
    }

    @Test
    fun `TapOnElementCommand description without relativePoint`() {
        // given
        val command = TapOnElementCommand(
            selector = ElementSelector(textRegex = "Cancel"),
            label = "Tap Cancel Button"
        )

        // when & then
        assertThat(command.originalDescription).isEqualTo("Tap on \"Cancel\"")
        assertThat(command.description()).isEqualTo("Tap Cancel Button")
    }

    @Test
    fun `TapOnElementCommand description with absolute coordinates`() {
        // given
        val command = TapOnElementCommand(
            selector = ElementSelector(idRegex = "submit-btn"),
            relativePoint = "10, 5",
            label = "Tap Submit at specific position"
        )

        // when & then
        assertThat(command.originalDescription).isEqualTo("Tap on id: submit-btn at 10, 5")
        assertThat(command.description()).isEqualTo("Tap Submit at specific position")
    }

    @Test
    fun `TapOnElementCommand description with CSS selector`() {
        // given
        val command = TapOnElementCommand(
            selector = ElementSelector(css = ".submit-button"),
            relativePoint = "50%, 90%",
            label = "Tap CSS element at specific position"
        )

        // when & then
        assertThat(command.originalDescription).isEqualTo("Tap on CSS: .submit-button at 50%, 90%")
        assertThat(command.description()).isEqualTo("Tap CSS element at specific position")
    }

    @Test
    fun `TapOnElementCommand description with size selector`() {
        // given
        val command = TapOnElementCommand(
            selector = ElementSelector(size = ElementSelector.SizeSelector(width = 100, height = 50)),
            relativePoint = "25%, 75%",
            label = "Tap sized element at specific position"
        )

        // when & then
        assertThat(command.originalDescription).isEqualTo("Tap on Size: 100x50 at 25%, 75%")
        assertThat(command.description()).isEqualTo("Tap sized element at specific position")
    }

    fun `description evaluates scripts in labels - GraalJS`(
        @YamlFile("029_command_descriptions.yaml") commands: List<Command>
    ) {
        val jsEngine = GraalJsEngine(platform = "ios")

        // Assert command with variable
        val assertCommand = commands[4] as AssertConditionCommand
        assertThat(assertCommand.originalDescription).isEqualTo("Assert that \${true} is true")
        assertThat(assertCommand.label).isEqualTo("\${\"Check that\".concat(\" \", \"true is still true\")}")
        val evaluatedAssert = assertCommand.evaluateScripts(jsEngine)
        assertThat(evaluatedAssert.label).isEqualTo("Check that true is still true")
        assertThat(evaluatedAssert.description()).isEqualTo("Check that true is still true")
        assertThat(evaluatedAssert.originalDescription).isEqualTo("Assert that true is true")

        jsEngine.close()
    }

    @Test
    fun `description evaluates scripts in labels - RhinoJS`(
        @YamlFile("029_command_descriptions.yaml") commands: List<Command>
    ) {
        val jsEngine = RhinoJsEngine(platform = "ios")

        // Assert command with variable
        val assertCommand = commands[4] as AssertConditionCommand
        assertThat(assertCommand.originalDescription).isEqualTo("Assert that \${true} is true")
        assertThat(assertCommand.label).isEqualTo("\${\"Check that\".concat(\" \", \"true is still true\")}")
        val evaluatedAssert = assertCommand.evaluateScripts(jsEngine)
        assertThat(evaluatedAssert.label).isEqualTo("Check that true is still true")
        assertThat(evaluatedAssert.description()).isEqualTo("Check that true is still true")
        assertThat(evaluatedAssert.originalDescription).isEqualTo("Assert that true is true")

        jsEngine.close()
    }
}