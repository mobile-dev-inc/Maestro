package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import maestro.device.Platform
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "start-session",
    description = [
        "Setup a Maestro Android session once so later commands can reuse the running driver.",
    ],
    hidden = true
)
class StartSessionCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    override fun call(): Int {
        TestDebugReporter.install(null, printToConsole = parent?.verbose == true)

        parent?.platform
            ?.takeIf { it.isNotBlank() }
            ?.let {
                if (Platform.fromString(it) != Platform.ANDROID) {
                    throw CliError("This command is only supported for Android devices.")
                }
            }

        MaestroSessionManager.newSession(
            host = parent?.host,
            port = parent?.port,
            driverHostPort = parent?.driverHostPort,
            deviceId = parent?.deviceId,
            platform = parent?.platform,
        ) { session ->
            val platform = session.device?.platform ?: session.maestro.cachedDeviceInfo.platform
            if (platform != Platform.ANDROID) {
                throw CliError("This command is only supported for Android devices.")
            }

            println("Maestro session started. Keep this running in the background.")
            println("Run your tests with `maestro test <flow.yaml>` in another terminal.")

            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(1000)
            }
        }

        return 0
    }
}
