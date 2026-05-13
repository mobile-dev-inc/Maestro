package maestro.cli

import picocli.CommandLine

class DebugOutputMixin {
    @CommandLine.Option(
        names = ["--debug-output"],
        description = ["Configures the debug output in this path, instead of default"]
    )
    var debugOutput: String? = null
}
