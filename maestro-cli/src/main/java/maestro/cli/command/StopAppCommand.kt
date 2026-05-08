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
    name = "stop-app",
    description = ["Stop a running app directly by app ID or bundle ID"]
)
class StopAppCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Parameters(index = "0", arity = "0..1", description = ["Bundle ID or app ID to stop"])
    private var appIdArg: String? = null

    @CommandLine.Option(names = ["--app-id", "--appId"], description = ["Bundle ID or app ID to stop"])
    private var appIdOption: String? = null

    override fun call(): Int {
        TestDebugReporter.install(null, printToConsole = parent?.verbose == true)

        val appId = appIdOption ?: appIdArg
            ?: throw CliError("App ID is required. Pass it as an argument or with --app-id")

        MaestroSessionManager.newSession(
            host = parent?.host,
            port = parent?.port,
            driverHostPort = null,
            deviceId = parent?.deviceId,
            platform = parent?.platform,
        ) { session ->
            val orchestra = Orchestra(session.maestro)
            val command = maestro.orchestra.StopAppCommand(
                appId = appId,
                label = null,
                optional = false,
            )

            runBlocking {
                orchestra.runFlow(listOf(MaestroCommand(command = command)))
            }
        }

        println("App stopped successfully: $appId")
        return 0
    }
}
