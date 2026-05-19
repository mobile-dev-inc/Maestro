package maestro.cli.command

import maestro.cli.App
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.devicecontrol.TapOnPerformer
import maestro.cli.report.TestDebugReporter
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.Orchestra
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "tap-on",
    description = ["Tap on a UI element directly using selector-style options"]
)
class TapOnCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Spec
    private lateinit var commandSpec: CommandLine.Model.CommandSpec

    @CommandLine.Option(names = ["--text"], description = ["Text content to match"])
    private var text: String? = null

    @CommandLine.Option(names = ["--id"], description = ["Element ID to match"])
    private var id: String? = null

    @CommandLine.Option(names = ["--index"], description = ["0-based index if multiple elements match the same criteria"])
    private var index: Int? = null

    @CommandLine.Option(names = ["--no-fuzzy"], description = ["Do not wrap text/id selectors in a partial-match regex"])
    private var noFuzzy: Boolean = false

    @CommandLine.Option(names = ["--enabled"], description = ["Match only enabled (true) or disabled (false) elements"])
    private var enabled: Boolean? = null

    @CommandLine.Option(names = ["--checked"], description = ["Match only checked (true) or unchecked (false) elements"])
    private var checked: Boolean? = null

    @CommandLine.Option(names = ["--focused"], description = ["Match only focused (true) or unfocused (false) elements"])
    private var focused: Boolean? = null

    @CommandLine.Option(names = ["--selected"], description = ["Match only selected (true) or unselected (false) elements"])
    private var selected: Boolean? = null

    override fun call(): Int {
        TestDebugReporter.install(null, printToConsole = parent?.verbose == true)

        if (text == null && id == null) {
            throw CommandLine.ParameterException(
                commandSpec.commandLine(),
                "Either --text or --id must be provided"
            )
        }

        val request = TapOnPerformer.Request(
            text = text,
            id = id,
            index = index,
            useFuzzyMatching = !noFuzzy,
            enabled = enabled,
            checked = checked,
            focused = focused,
            selected = selected,
        )

        MaestroSessionManager.newSession(
            host = parent?.host,
            port = parent?.port,
            driverHostPort = null,
            deviceId = parent?.deviceId,
            platform = parent?.platform,
        ) { session ->
            val orchestra = Orchestra(session.maestro)
            TapOnPerformer.perform(orchestra, request)
        }

        println("Tap executed successfully")
        return 0
    }
}
