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
            println("HERE")
            println(commands)
            val tapCommand = commands[1] as TapOnElementCommand
            assertThat(tapCommand.label).isEqualTo("Scroll to find the maybe-later button")
            val inputCommand = commands[2] as InputTextCommand
            assertThat(inputCommand.originalDescription).isEqualTo("Input text \${username}")
            val assertCommand = commands[3] as AssertConditionCommand
            assertThat(assertCommand.originalDescription).isEqualTo("Assert that \"Hello \${username}\" is visible")
    }

    @Test
    fun `description uses label when available`(
        @YamlFile("028_command_descriptions.yaml") commands: List<Command>
    ) {
        val tapCommand = commands[1] as TapOnElementCommand
        assertThat(tapCommand.description()).isEqualTo("Scroll to find the maybe-later button")

        val inputCommand = commands[2] as InputTextCommand
        assertThat(inputCommand.description()).isEqualTo("Input text \${username}")
    }

    @Test
    fun `description evaluates script variables`(
        @YamlFile("028_command_descriptions.yaml") commands: List<Command>
    ) {
        val jsEngine = RhinoJsEngine(platform = "ios")
        jsEngine.putEnv("username", "John")

        val inputCommand = commands[2] as InputTextCommand
        val evaluatedInput = inputCommand.evaluateScripts(jsEngine)
        assertThat(evaluatedInput.description()).isEqualTo("Input text John")

        val assertCommand = commands[3] as AssertConditionCommand
        val evaluatedAssert = assertCommand.evaluateScripts(jsEngine)
        assertThat(evaluatedAssert.description()).isEqualTo("Assert that \"Hello John\" is visible")

        jsEngine.close()
    }
}