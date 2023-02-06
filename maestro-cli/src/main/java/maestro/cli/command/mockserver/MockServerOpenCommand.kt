package maestro.cli.command.mockserver

import maestro.cli.CliError
import maestro.cli.api.ApiClient
import maestro.cli.cloud.CloudInteractor
import maestro.cli.session.MaestroSessionManager
import maestro.cli.util.PrintUtils
import maestro.cli.view.blue
import maestro.cli.view.bold
import maestro.cli.view.box
import maestro.cli.view.faint
import maestro.cli.view.red
import maestro.networkproxy.NetworkProxy
import maestro.networkproxy.yaml.YamlMappingRuleParser
import maestro.studio.MaestroStudio
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.awt.Desktop
import java.io.File
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.Callable

@Command(
    name = "open",
)
class MockServerOpenCommand : Callable<Int> {

    @ParentCommand
    lateinit var parent: MockServerCommand

    override fun call(): Int {
        MaestroSessionManager.newSession(null, null, null, true) { session ->
            val port = getFreePort()
            MaestroStudio.start(port, session.maestro)

            val studioUrl = "http://localhost:${port}/mock"
            val message = ("Maestro Studio".bold() + " Mock Server is running at " + studioUrl.blue()).box()
            println()
            println(message)
            tryOpenUrl(studioUrl)

            println()
            println("Navigate to $studioUrl in your browser to open Maestro Studio Mock Server. Ctrl-C to exit.".faint())

            Thread.currentThread().join()
        }
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