package maestro.cli.command

import maestro.cli.App
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.interactive.DeviceControlPerformer
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "back",
    description = ["Press the device back/navigation button"]
)
class BackCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    override fun call(): Int {
        TestDebugReporter.install(null, printToConsole = parent?.verbose == true)

        MaestroSessionManager.newSession(
            host = parent?.host,
            port = parent?.port,
            driverHostPort = null,
            deviceId = parent?.deviceId,
            platform = parent?.platform,
        ) { session ->
            DeviceControlPerformer.back(session.maestro)
        }

        println("Back button pressed successfully")
        return 0
    }
}
