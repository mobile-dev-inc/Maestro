package maestro.cli.command

import maestro.auth.ApiKey
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.analytics.Analytics
import maestro.cli.analytics.UserLoggedOutEvent
import picocli.CommandLine
import java.util.concurrent.Callable
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

    override fun call(): Int {
        // Track logout event before deleting the token
        Analytics.trackEvent(UserLoggedOutEvent())

        ApiKey.setToken(null)

        message("Logged out.")

        return 0
    }

}
