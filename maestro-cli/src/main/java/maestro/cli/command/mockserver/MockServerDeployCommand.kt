package maestro.cli.command.mockserver

import maestro.cli.api.ApiClient
import maestro.cli.cloud.CloudInteractor
import maestro.cli.view.red
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

    @Parameters(arity = "0..1")
    lateinit var workspace: File

    @Option(order = 0, names = ["--apiKey"], description = ["API key"])
    private var apiKey: String? = null

    @Option(order = 1, names = ["--apiUrl"], description = ["API base URL"])
    private var apiUrl: String = "https://api.mobile.dev"

    @CommandLine.Spec
    lateinit var commandSpec: CommandLine.Model.CommandSpec

    private fun getDefaultMockserverDir(): File {
        val currentDir = System.getProperty("user.dir")
        val defaultWorkspaceDirPath = "$currentDir/.maestro/mockserver"

        return File(defaultWorkspaceDirPath)
    }

    override fun call(): Int {
        println()
        println("Maestro Mock Server has been deprecated and will be removed in a future version".red())
        println()

        if (!this::workspace.isInitialized) {
            workspace = getDefaultMockserverDir()
        }

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
    }

}