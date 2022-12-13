package maestro.cli.command

import maestro.cli.DisableAnsiMixin
import maestro.cli.api.ApiClient
import maestro.cli.auth.Auth
import maestro.cli.util.PrintUtils.message
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "login",
    description = [
        "Log into Maestro Cloud"
    ]
)
class LoginCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Option(names = ["--apiUrl"], description = ["API base URL"])
    private var apiUrl: String = "https://api.mobile.dev"

    private val auth by lazy {
        Auth(ApiClient(apiUrl))
    }

    override fun call(): Int {
        val existingToken = auth.getCachedAuthToken()

        if (existingToken != null) {
            message("Already logged in. Run \"maestro logout\" to logout.")
            return 0
        }

        auth.triggerSignInFlow()

        return 0
    }

}
