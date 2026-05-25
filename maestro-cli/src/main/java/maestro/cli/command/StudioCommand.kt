package maestro.cli.command

import maestro.cli.App
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.util.studioDownloadUrlForCurrentOs
import maestro.cli.view.bold
import picocli.CommandLine
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "studio",
    hidden = true,
    description = ["Launch Maestro Studio"],
)
class StudioCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    override fun call(): Int {
        println()
        println("Maestro Studio is no longer bundled with the CLI.".bold())
        println("Download the new Maestro Studio desktop app instead:")
        println()
        println("  ${studioDownloadUrlForCurrentOs()}".bold())
        println()
        return 0
    }
}
