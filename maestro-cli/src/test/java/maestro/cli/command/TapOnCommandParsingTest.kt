package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import picocli.CommandLine

class TapOnCommandParsingTest {

    @Test
    fun `call fails without selector args`() {
        val commandObj = TapOnCommand()
        CommandLine(commandObj).parseArgs()

        val ex = assertThrows<CommandLine.ParameterException> {
            commandObj.call()
        }

        assertThat(ex.message).contains("Either --text or --id must be provided")
    }

    @Test
    fun `parses fuzzy matching toggle and selector fields`() {
        val commandObj = TapOnCommand()
        val command = CommandLine(commandObj)

        command.parseArgs(
            "--text", "Login",
            "--id", "login_button",
            "--index", "1",
            "--no-fuzzy",
            "--enabled=true",
            "--checked=false",
            "--focused=true",
            "--selected=false",
        )

        val textField = commandObj.javaClass.getDeclaredField("text").apply { isAccessible = true }
        val idField = commandObj.javaClass.getDeclaredField("id").apply { isAccessible = true }
        val indexField = commandObj.javaClass.getDeclaredField("index").apply { isAccessible = true }
        val noFuzzyField = commandObj.javaClass.getDeclaredField("noFuzzy").apply { isAccessible = true }
        val enabledField = commandObj.javaClass.getDeclaredField("enabled").apply { isAccessible = true }
        val checkedField = commandObj.javaClass.getDeclaredField("checked").apply { isAccessible = true }
        val focusedField = commandObj.javaClass.getDeclaredField("focused").apply { isAccessible = true }
        val selectedField = commandObj.javaClass.getDeclaredField("selected").apply { isAccessible = true }

        assertThat(textField.get(commandObj) as String?).isEqualTo("Login")
        assertThat(idField.get(commandObj) as String?).isEqualTo("login_button")
        assertThat(indexField.get(commandObj) as Int?).isEqualTo(1)
        assertThat(noFuzzyField.get(commandObj) as Boolean).isTrue()
        assertThat(enabledField.get(commandObj) as Boolean?).isTrue()
        assertThat(checkedField.get(commandObj) as Boolean?).isFalse()
        assertThat(focusedField.get(commandObj) as Boolean?).isTrue()
        assertThat(selectedField.get(commandObj) as Boolean?).isFalse()
    }
}
