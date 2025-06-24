package maestro.cli.command

import maestro.auth.ApiKey
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.api.ApiClient
import maestro.cli.auth.Auth
import maestro.cli.util.PrintUtils.message
import picocli.CommandLine
import java.util.concurrent.Callable
import kotlin.io.path.absolutePathString
import maestro.cli.report.TestDebugReporter
import maestro.debuglog.LogConfig
import picocli.CommandLine.Option

@CommandLine.Command(
    name = "login",
    description = [
        "Log into Maestro Cloud"
    ]
)
class LoginCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @Option(names = ["--api-url", "--apiUrl"], description = ["API base URL"])
    private var apiUrl: String = "https://api.copilot.mobile.dev"

    private val auth by lazy {
        Auth(ApiClient("$apiUrl/v2"))
    }

    override fun call(): Int {
        LogConfig.configure(logFileName = null, printToConsole = false) // Disable all logs from Login

        val existingToken = ApiKey.getToken()

        if (existingToken != null) {
            message("Already logged in. Run \"maestro logout\" to logout.")
            return 0
        }

        val token = auth.triggerSignInFlow()
        println(token)

        return 0
    }

}
