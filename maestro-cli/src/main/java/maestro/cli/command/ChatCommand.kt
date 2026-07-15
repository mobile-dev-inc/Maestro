package maestro.cli.command

import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "chat",
    description = [
        "Discontinued. Use Maestro MCP instead: https://docs.maestro.dev/get-started/maestro-mcp"
    ]
)
class ChatCommand : Callable<Int> {

    // Options are accepted and ignored so existing invocations still reach the message below
    // rather than dying on a picocli "Unknown option" usage dump.
    @CommandLine.Option(order = 0, names = ["--api-key", "--apiKey"], description = ["Ignored. maestro chat is discontinued."])
    private var apiKey: String? = null

    @CommandLine.Option(order = 1, names = ["--api-url", "--apiUrl"], description = ["Ignored. maestro chat is discontinued."])
    private var apiUrl: String? = null

    @CommandLine.Option(order = 2, names = ["--ask"], description = ["Ignored. maestro chat is discontinued."])
    private var ask: String? = null

    override fun call(): Int {
        // --ask is the scripted form and may be wired into customer CI. Exiting 0 with prose
        // where a script expects an answer would hide the breakage; fail loudly instead. A
        // human running `maestro chat` interactively isn't scripting anything, so they get a
        // clean exit 0.
        return if (ask != null) {
            System.err.println(MESSAGE)
            1
        } else {
            println(MESSAGE)
            0
        }
    }

    companion object {
        // Reviewed copy — do not paraphrase.
        private val MESSAGE = """
            maestro chat has been discontinued.

            MaestroGPT answered questions about Maestro. Maestro MCP does more: it connects
            your coding agent (Claude Code, Cursor, Codex, and others) directly to Maestro so
            it can write, run, and debug your flows for you.

            Get started: https://docs.maestro.dev/get-started/maestro-mcp
        """.trimIndent()
    }
}
