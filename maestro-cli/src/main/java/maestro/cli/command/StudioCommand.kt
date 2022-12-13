package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.DisableAnsiMixin
import maestro.cli.util.MaestroFactory
import maestro.cli.view.blue
import maestro.cli.view.bold
import maestro.cli.view.box
import maestro.cli.view.faint
import maestro.cli.view.red
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

    override fun call(): Int {
        if (parent?.platform != null) {
            throw CliError("--platform option was deprecated. You can remove it to run your test.")
        }

        val (maestro, _) = MaestroFactory.createMaestro(parent?.host, parent?.port, parent?.deviceId)

        val port = getFreePort()
        MaestroStudio.start(port, maestro)

        val studioUrl = "http://localhost:${port}"
        val message = ("Maestro Studio".bold() + " is running at " + studioUrl.blue()).box()
        println()
        println(message)
        if (Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(studioUrl))
        }

        println()
        println("Note: Most Maestro CLI commands do not work while Maestro Studio is running. We will address this in an upcoming release.")

        println()
        println("Navigate to $studioUrl in your browser to open Maestro Studio. Ctrl-C to exit.".faint())

        Thread.currentThread().join()
        return 0
    }

    private fun getFreePort(): Int {
        (9999..11000).forEach { port ->
            ServerSocket(port).use { return it.localPort }
        }
        ServerSocket(0).use { return it.localPort }
    }
}
