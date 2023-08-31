package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.DisableAnsiMixin
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import maestro.cli.view.blue
import maestro.cli.view.bold
import maestro.cli.view.box
import maestro.cli.view.faint
import maestro.studio.MaestroStudio
import picocli.CommandLine
import java.awt.Desktop
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "studio",
    hidden = true,
    description = ["Launch Maestro Studio"],
)
class StudioCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Option(
        names = ["--debug-output"],
        description = ["Configures the debug output in this path, instead of default"]
    )
    private var debugOutput: String? = null

    @CommandLine.Option(
        names = ["--no-window"],
        description = ["When set, a browser window will not be automatically opened"]
    )
    private var noWindow: Boolean? = null

    override fun call(): Int {
        if (parent?.platform != null) {
            throw CliError("--platform option was deprecated. You can remove it to run your test.")
        }

        TestDebugReporter.install(debugOutputPathAsString = debugOutput)

        MaestroSessionManager.newSession(parent?.host, parent?.port, parent?.deviceId, true) { session ->
            val port = getFreePort()
            MaestroStudio.start(port, session.maestro)

            val studioUrl = "http://localhost:${port}"
            val message = ("Maestro Studio".bold() + " is running at " + studioUrl.blue()).box()
            println()
            println(message)
            tryOpenUrl(studioUrl)

            println()
            println("Tip: Maestro Studio can now run simultaneously alongside other Maestro CLI commands!")

            println()
            println("Navigate to $studioUrl in your browser to open Maestro Studio. Ctrl-C to exit.".faint())

            Thread.currentThread().join()
        }

        TestDebugReporter.deleteOldFiles()
        return 0
    }

    private fun tryOpenUrl(studioUrl: String) {
        try {
            if (Desktop.isDesktopSupported() && noWindow != true) {
                Desktop.getDesktop().browse(URI(studioUrl))
            }
        } catch (ignore: Exception) {
            // Do nothing
        }
    }

    private fun getFreePort(): Int {
        (9999..11000).forEach { port ->
            try {
                ServerSocket(port).use { return it.localPort }
            } catch (ignore: Exception) {}
        }
        ServerSocket(0).use { return it.localPort }
    }
}
