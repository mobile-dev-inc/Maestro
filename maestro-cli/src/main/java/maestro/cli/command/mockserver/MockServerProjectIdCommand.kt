package maestro.cli.command.mockserver

import maestro.cli.util.PrintUtils
import maestro.cli.util.PrintUtils.err
import maestro.mockserver.MockInteractor
import picocli.CommandLine.Command
import picocli.CommandLine.ParentCommand
import java.util.concurrent.Callable

@Command(
    name = "projectid",
)
class MockServerProjectIdCommand : Callable<Int> {

    @ParentCommand
    lateinit var parent: MockServerCommand

    private val interactor = MockInteractor()

    override fun call(): Int {
        val projectId = interactor.getProjectId() ?: err("Could not retrieve project id")

        PrintUtils.message("Project id: $projectId")
        PrintUtils.message("Run `maestro mockserver open` to get started with Maestro Mock Server!")

        return 0
    }
}