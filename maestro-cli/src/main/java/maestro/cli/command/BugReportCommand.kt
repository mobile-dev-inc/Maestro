package maestro.cli.command

import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.debuglog.DebugLogStore
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "bugreport",
    description = [
        "Report a bug - Help us improve your experience!"
    ]
)
class BugReportCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    override fun call(): Int {
        val message = """
            Please open an issue on github: https://github.com/mobile-dev-inc/maestro/issues/new
            Attach the files found in this folder ${DebugLogStore.logDirectory}
            """.trimIndent()
        println(message)
        return 0
    }

}
