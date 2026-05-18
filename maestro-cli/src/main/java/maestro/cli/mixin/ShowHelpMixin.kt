package maestro.cli.mixin

import picocli.CommandLine

class ShowHelpMixin {
    @CommandLine.Option(
        names = ["-h", "--help"],
        usageHelp = true,
        description = ["Display help message"],
    )
    var help = false
}
