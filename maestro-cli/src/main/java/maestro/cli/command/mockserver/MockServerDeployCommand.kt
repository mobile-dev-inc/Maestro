package maestro.cli.command.mockserver

import maestro.cli.api.ApiClient
import maestro.cli.cloud.CloudInteractor
import maestro.cli.session.MaestroSessionManager
import maestro.cli.util.PrintUtils
import maestro.cli.view.red
import maestro.networkproxy.NetworkProxy
import maestro.networkproxy.yaml.YamlMappingRuleParser
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import picocli.CommandLine.ParentCommand
import java.io.File
import java.util.concurrent.Callable

@Command(
    name = "deploy",
)
class MockServerDeployCommand : Callable<Int> {

    @ParentCommand
    lateinit var parent: MockServerCommand

    @Parameters
    lateinit var workspace: File

    @Option(order = 0, names = ["--apiKey"], description = ["API key"])
    private var apiKey: String? = null

    @Option(order = 1, names = ["--apiUrl"], description = ["API base URL"])
    private var apiUrl: String = "https://api.mobile.dev"

    @CommandLine.Spec
    lateinit var commandSpec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        if (!workspace.isDirectory) {
            throw CommandLine.ParameterException(
                commandSpec.commandLine(),
                "Not a directory: $workspace"
            )
        }

        return CloudInteractor(
            client = ApiClient(apiUrl),
        ).deployMaestroMockServerWorkspace(
            workspace = workspace,
            apiKey = apiKey,
        )

        return 0
    }

}