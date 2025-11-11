package maestro.cli.command

import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.analytics.Analytics
import maestro.cli.analytics.UserLoggedOutEvent
import org.fusesource.jansi.Ansi
import picocli.CommandLine
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable
import kotlin.io.path.deleteIfExists
import maestro.cli.util.PrintUtils
import maestro.cli.util.PrintUtils.message

@CommandLine.Command(
    name = "logout",
    description = [
        "Log out of Maestro Cloud"
    ]
)
class LogoutCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    private val cachedAuthTokenFile: Path = Paths.get(System.getProperty("user.home"), ".mobiledev", "authtoken")

    override fun call(): Int {
        // Track logout event before deleting the token
        Analytics.trackEvent(UserLoggedOutEvent())
        
        cachedAuthTokenFile.deleteIfExists()

        message("Logged out.")

        return 0
    }

}
