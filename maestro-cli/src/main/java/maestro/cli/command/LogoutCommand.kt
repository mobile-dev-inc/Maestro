package maestro.cli.command

import maestro.cli.DisableAnsiMixin
import org.fusesource.jansi.Ansi
import picocli.CommandLine
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable
import kotlin.io.path.deleteIfExists

@CommandLine.Command(
    name = "logout",
    description = [
        "Log out of Maestro Cloud"
    ]
)
class LogoutCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    private val cachedAuthTokenFile: Path = Paths.get(System.getProperty("user.home"), ".mobiledev", "authtoken")

    override fun call(): Int {
        cachedAuthTokenFile.deleteIfExists()

        message("Logged out")

        return 0
    }

    companion object {

        // TODO reuse
        private fun message(message: String) {
            println(Ansi.ansi().render("@|cyan \n$message|@"))
        }

    }

}
