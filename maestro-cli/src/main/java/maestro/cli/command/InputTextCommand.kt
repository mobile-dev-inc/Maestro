package maestro.cli.command

import maestro.cli.App
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.devicecontrol.DirectDeviceCommandSupport
import maestro.cli.devicecontrol.DeviceControlPerformer
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "input-text",
    description = ["Input text into the currently focused field"]
)
class InputTextCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Spec
    private lateinit var commandSpec: CommandLine.Model.CommandSpec

    @CommandLine.Parameters(index = "0", arity = "0..1", description = ["Text to input"])
    private var textArg: String? = null

    @CommandLine.Option(names = ["--text"], description = ["Text to input"])
    private var textOption: String? = null

    override fun call(): Int {
        TestDebugReporter.install(null, printToConsole = parent?.verbose == true)

        val text = DirectDeviceCommandSupport.resolveRequiredValue(
            optionValue = textOption,
            argumentValue = textArg,
            valueName = "Text",
            optionName = "--text",
            commandLine = commandSpec.commandLine(),
        )

        MaestroSessionManager.newSession(
            host = parent?.host,
            port = parent?.port,
            driverHostPort = null,
            deviceId = parent?.deviceId,
            platform = parent?.platform,
        ) { session ->
            DeviceControlPerformer.inputText(session.maestro, text)
        }

        println("Text input successful")
        return 0
    }
}
