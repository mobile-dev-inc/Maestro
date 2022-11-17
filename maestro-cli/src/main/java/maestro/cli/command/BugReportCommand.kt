package maestro.cli.command

import maestro.cli.App
import maestro.cli.debuglog.DebugLogStore
import picocli.CommandLine
import java.io.File
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "bugreport",
)
class BugReportCommand : Callable<Int> {

    override fun call(): Int {
        val message = """
            Please open an issue on github: https://github.com/mobile-dev-inc/maestro/issues/new
            Attach the files found in this folder ${DebugLogStore.logDirectory}
            """.trimIndent()
        println(message)
        return 0
    }

}
