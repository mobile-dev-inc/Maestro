package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.interactive.DeviceControlPerformer
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import picocli.CommandLine
import java.util.concurrent.Callable

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
            DeviceControlPerformer.stopApp(session.maestro, appId)
        }

        println("App stopped successfully: $appId")
        return 0
    }
}
