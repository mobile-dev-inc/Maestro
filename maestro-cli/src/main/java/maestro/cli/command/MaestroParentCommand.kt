package maestro.cli.command

import picocli.CommandLine

@CommandLine.Command
class MaestroParentCommand {
    @CommandLine.Option(names = ["-p", "--platform"])
    var platform: String? = null

    @CommandLine.Option(names = ["--host"])
    var host: String = "localhost"

    @CommandLine.Option(names = ["--port"])
    var port: Int? = null
}
