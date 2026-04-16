package maestro.cli.command

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import okio.sink
import picocli.CommandLine
import java.io.File
import java.util.concurrent.Callable
import kotlinx.coroutines.runBlocking

@CommandLine.Command(
    name = "take-screenshot",
    description = ["Take a screenshot of the current device screen and save it to a file"]
)
class TakeScreenshotCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Option(
        names = ["--output"],
        required = true,
        description = ["Output file path for the screenshot"]
    )
    private lateinit var output: File

    override fun call(): Int {
        TestDebugReporter.install(null, printToConsole = parent?.verbose == true)

        val parentDir = output.absoluteFile.parentFile
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            throw CliError("Unable to create output directory: ${parentDir.absolutePath}")
        }

        MaestroSessionManager.newSession(
            host = parent?.host,
            port = parent?.port,
            driverHostPort = null,
            deviceId = parent?.deviceId,
            platform = parent?.platform,
        ) { session ->
            output.sink().use { sink ->
                runBlocking {
                    session.maestro.takeScreenshot(sink, false)
                }
            }
        }

        println("Screenshot saved to ${output.absolutePath}")
        return 0
    }
}
