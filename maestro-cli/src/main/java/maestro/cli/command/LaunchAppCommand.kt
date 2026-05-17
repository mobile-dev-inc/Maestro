package maestro.cli.command

import maestro.cli.App
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.devicecontrol.DirectDeviceCommandSupport
import maestro.cli.devicecontrol.DeviceControlPerformer
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import picocli.CommandLine
import java.util.concurrent.Callable

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

    @CommandLine.Spec
    private lateinit var commandSpec: CommandLine.Model.CommandSpec

    @CommandLine.Parameters(index = "0", arity = "0..1", description = ["Bundle ID or app ID to launch"])
    private var appIdArg: String? = null

    @CommandLine.Option(names = ["--app-id", "--appId"], description = ["Bundle ID or app ID to launch"])
    private var appIdOption: String? = null

    override fun call(): Int {
        TestDebugReporter.install(null, printToConsole = parent?.verbose == true)

        val appId = DirectDeviceCommandSupport.resolveRequiredValue(
            optionValue = appIdOption,
            argumentValue = appIdArg,
            valueName = "App ID",
            optionName = "--app-id",
            commandLine = commandSpec.commandLine(),
        )

        MaestroSessionManager.newSession(
            host = parent?.host,
            port = parent?.port,
            driverHostPort = null,
            deviceId = parent?.deviceId,
            platform = parent?.platform,
        ) { session ->
            DeviceControlPerformer.launchApp(session.maestro, appId)
        }

        println("App launched successfully: $appId")
        return 0
    }
}
