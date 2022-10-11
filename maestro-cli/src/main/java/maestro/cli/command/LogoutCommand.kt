package maestro.cli.command

import org.fusesource.jansi.Ansi
import picocli.CommandLine
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable
import kotlin.io.path.deleteIfExists

@CommandLine.Command(
    name = "logout",
)
class LogoutCommand : Callable<Int> {

    private val cachedAuthTokenFile: Path = Paths.get(System.getProperty("user.home"), ".mobiledev", "authtoken")

    override fun call(): Int {
        cachedAuthTokenFile.deleteIfExists()

        message("Logged out")

        return 0
    }

    companion object {

        // TODO reuse
        private fun message(message: String) {
            println(Ansi.ansi().render("@|cyan \n$message |@"))
        }

    }

}