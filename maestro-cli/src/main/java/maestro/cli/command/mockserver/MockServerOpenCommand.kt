package maestro.cli.command.mockserver

import maestro.Driver
import maestro.Maestro
import maestro.cli.session.MaestroSessionManager
import maestro.cli.view.blue
import maestro.cli.view.bold
import maestro.cli.view.box
import maestro.cli.view.faint
import maestro.studio.MaestroStudio
import maestro.studio.MockInteractor
import picocli.CommandLine.Command
import picocli.CommandLine.ParentCommand
import java.awt.Desktop
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.Callable

@Command(
    name = "open",
)
class MockServerOpenCommand : Callable<Int> {

    @ParentCommand
    lateinit var parent: MockServerCommand

    private val interactor = MockInteractor()

    override fun call(): Int {
        interactor.getProjectId() ?: error("Not logged in. Please run `maestro login` and try again.")

        val port = getFreePort()
        MaestroStudio.start(port, null)

        val studioUrl = "http://localhost:${port}/mock"
        val message = ("Maestro Studio".bold() + " Mock Server is running at " + studioUrl.blue()).box()
        println()
        println(message)
        tryOpenUrl(studioUrl)

        println()
        println("Navigate to $studioUrl in your browser to open Maestro Studio Mock Server. Ctrl-C to exit.".faint())

        return 0
    }

    private fun tryOpenUrl(studioUrl: String) {
        try {
            if (Desktop.isDesktopSupported()) {
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