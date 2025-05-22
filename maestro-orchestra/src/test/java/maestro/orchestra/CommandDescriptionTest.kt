package maestro.orchestra

import com.google.common.truth.Truth.assertThat
import maestro.js.RhinoJsEngine
import maestro.orchestra.yaml.junit.YamlFile
import maestro.orchestra.yaml.junit.YamlCommandsExtension
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(YamlCommandsExtension::class)
internal class CommandDescriptionTest {

    @Test
    fun `original description contains raw command details`(
        @YamlFile("028_command_descriptions.yaml") commands: List<Command>
    ) {
        val jsEngine = RhinoJsEngine(platform = "ios")
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
        @YamlFile("028_command_descriptions.yaml") commands: List<Command>
    ) {
        val jsEngine = RhinoJsEngine(platform = "ios")
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
        @YamlFile("028_command_descriptions.yaml") commands: List<Command>
    ) {
        val jsEngine = RhinoJsEngine(platform = "ios")
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
}