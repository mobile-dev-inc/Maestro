package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import picocli.CommandLine
import java.util.concurrent.Callable
import kotlinx.coroutines.runBlocking

@CommandLine.Command(
    name = "input-text",
    description = ["Input text into the currently focused field"]
)
class InputTextCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Parameters(index = "0", arity = "0..1", description = ["Text to input"])
    private var textArg: String? = null

    @CommandLine.Option(names = ["--text"], description = ["Text to input"])
    private var textOption: String? = null

    override fun call(): Int {
        TestDebugReporter.install(null, printToConsole = parent?.verbose == true)

        val text = textOption ?: textArg
            ?: throw CliError("Text is required. Pass it as an argument or with --text")

        MaestroSessionManager.newSession(
            host = parent?.host,
            port = parent?.port,
            driverHostPort = null,
            deviceId = parent?.deviceId,
            platform = parent?.platform,
        ) { session ->
            val orchestra = Orchestra(session.maestro)
            val command = maestro.orchestra.InputTextCommand(
                text = text,
                label = null,
                optional = false,
            )

            runBlocking {
                orchestra.runFlow(listOf(MaestroCommand(command = command)))
            }
        }

        println("Text input successful")
        return 0
    }
}
