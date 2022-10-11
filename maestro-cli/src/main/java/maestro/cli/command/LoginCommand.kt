package maestro.cli.command

import maestro.cli.api.ApiClient
import maestro.cli.auth.Auth
import maestro.cli.util.PrintUtils.message
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "login",
)
class LoginCommand : Callable<Int> {

    @CommandLine.Option(names = ["--apiUrl"], description = ["API base URL"])
    private var apiUrl: String = "https://api.mobile.dev"

    private val auth by lazy {
        Auth(ApiClient(apiUrl))
    }

    override fun call(): Int {
        val existingToken = auth.getCachedAuthToken()

        if (existingToken != null) {
            message("Already logged in")
            return 0
        }

        auth.triggerSignInFlow()

        return 0
    }

}