package maestro.orchestra.yaml

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.reflect.full.primaryConstructor

/**
 * The two places the parser consults its own list of commands, both driven by `objectCommands` /
 * `allCommands` in [MaestroFlowParser]: the "Missing Command Options" error for a command written bare
 * that needs a value, and the "Did you mean" suggestion for one it does not recognise.
 *
 * Between them they had a single whole-workspace snapshot -- `e020`, for `tapOn` -- and the snapshot
 * covering the suggestion path uses `invalidCommand`, which resembles nothing, so no test exercised the
 * prefix, substring or edit-distance matching at all. This pins what the parser does today.
 */
class CommandSurfaceErrorsTest {

    @Test
    fun `a command that needs a value is rejected for missing options, not as unknown`() {
        assertThat(errorFor("tapOn")).contains("The command `tapOn` requires additional options.")
    }

    @Test
    fun `a command that may be written bare parses`() {
        assertThat(errorFor("back")).isNull()
    }

    /**
     * The contract of the parser's command list: every command YamlFluentCommand declares is one the
     * parser recognises. A command that fell out of the list would be reported as though the user had
     * invented it -- "`tapOn` is not a valid command" -- which is the worst error Maestro can give.
     */
    @Test
    fun `no declared command is ever reported as invalid`() {
        val misreported = YamlFluentCommand::class.primaryConstructor!!.parameters
            .mapNotNull { it.name }
            .filterNot { it.startsWith("_") }
            .filter { errorFor(it)?.contains("is not a valid command") == true }

        assertThat(misreported).isEmpty()
    }

    @Test
    fun `an unknown command is reported as invalid`() {
        assertThat(errorFor("notARealCommand")).contains("`notARealCommand` is not a valid command.")
    }

    @Test
    fun `a misspelled command suggests the one it resembles`() {
        assertThat(errorFor("tapOnn")).contains("Did you mean `tapOn`?")
    }

    @Test
    fun `a command name too short to disambiguate suggests nothing`() {
        val error = errorFor("xy")

        assertThat(error).contains("is not a valid command")
        assertThat(error).doesNotContain("Did you mean")
    }

    // ------------------------------------------ what the error says now that it reads the schema

    @Test
    fun `missing options names the required argument`() {
        assertThat(errorFor("inputText")).contains("Requires `text`.")
    }

    @Test
    fun `missing options names the alternatives when the command needs one of several`() {
        assertThat(errorFor("runFlow")).contains("Requires one of `file`, `commands`.")
        assertThat(errorFor("extendedWaitUntil")).contains("Requires one of `visible`, `notVisible`.")
    }

    @Test
    fun `missing options names a single-member requirement without the one-of phrasing`() {
        val error = errorFor("addMedia")

        assertThat(error).contains("Requires `files`.")
        assertThat(error).doesNotContain("one of")
    }

    @Test
    fun `missing options says a selector command takes a selector`() {
        assertThat(errorFor("tapOn")).contains("It takes an element selector")
    }

    /**
     * The link is carried on the exception rather than inside the message, which is what the CLI and the
     * workspace planner render from -- asserting on the message would pass for the wrong reason.
     */
    @Test
    fun `missing options links to the command reference`() {
        val thrown = runCatching {
            MaestroFlowParser.parseFlow(Paths.get("test.yaml"), "appId: com.example.app\n---\n- inputText\n")
        }.exceptionOrNull()

        assertThat((thrown as FlowParseException).docs)
            .isEqualTo("https://docs.maestro.dev/api-reference/commands")
    }

    /** The parse error for a flow whose only command is [command] written bare, or null if it parses. */
    private fun errorFor(command: String): String? = runCatching {
        MaestroFlowParser.parseFlow(Paths.get("test.yaml"), "appId: com.example.app\n---\n- $command\n")
    }.exceptionOrNull()?.message
}
