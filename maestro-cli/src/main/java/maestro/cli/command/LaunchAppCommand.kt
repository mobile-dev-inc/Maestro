package maestro.cli.command

import maestro.cli.App
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
    name = "launch-app",
    description = ["Launch an installed app directly by app ID or bundle ID"]
)
class LaunchAppCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Parameters(index = "0", arity = "0..1", description = ["Bundle ID or app ID to launch"])
    private var appIdArg: String? = null

    @CommandLine.Option(names = ["--app-id", "--appId"], description = ["Bundle ID or app ID to launch"])
    private var appIdOption: String? = null

    override fun call(): Int {
        TestDebugReporter.install(null, printToConsole = parent?.verbose == true)

        val appId = appIdOption ?: appIdArg
            ?: throw maestro.cli.CliError("App ID is required. Pass it as an argument or with --app-id")

        MaestroSessionManager.newSession(
            host = parent?.host,
            port = parent?.port,
            driverHostPort = null,
            deviceId = parent?.deviceId,
            platform = parent?.platform,
        ) { session ->
            val orchestra = Orchestra(session.maestro)
            val command = maestro.orchestra.LaunchAppCommand(
                appId = appId,
                clearState = null,
                clearKeychain = null,
                stopApp = null,
                permissions = null,
                launchArguments = null,
                label = null,
                optional = false,
            )

            runBlocking {
                orchestra.runFlow(listOf(MaestroCommand(command = command)))
            }
        }

        println("App launched successfully: $appId")
        return 0
    }
}
