package maestro.cli.mixin

import picocli.CommandLine

class EnvMixin {
    @CommandLine.Option(names = ["-e", "--env"], description = ["Environment variables to inject into your Flows"])
    var env: Map<String, String> = emptyMap()
}
