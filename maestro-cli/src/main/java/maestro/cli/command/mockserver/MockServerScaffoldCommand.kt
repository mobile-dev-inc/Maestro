package maestro.cli.command.mockserver

import maestro.cli.api.ApiClient
import maestro.cli.cloud.CloudInteractor
import maestro.cli.util.PrintUtils
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.ParentCommand
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Callable

@Command(
    name = "scaffold",
)
class MockServerScaffoldCommand : Callable<Int> {

    @ParentCommand
    lateinit var parent: MockServerCommand

    @CommandLine.Option(order = 0, names = ["--apiKey"], description = ["API key"])
    private var apiKey: String? = null

    @CommandLine.Option(order = 1, names = ["--apiUrl"], description = ["API base URL"])
    private var apiUrl: String = "https://api.mobile.dev"

    private fun getWorkspaceDirectory(): File {
        val currentDir = System.getProperty("user.dir")
        val maestroDirPath = "$currentDir/.maestro"
        return if (File(maestroDirPath).isDirectory) {
            val mockserverDir = File("$maestroDirPath/mockserver")
            if (mockserverDir.isDirectory && (mockserverDir.list()?.size ?: 0) > 0) {
                error("Found a non-empty mockserver directory, aborting scaffolding")
            }
            Files.createDirectory(mockserverDir.toPath()).toFile()
        } else {
            val sampleDirectory = File("$currentDir/sample-workspace")
            if (sampleDirectory.isDirectory) error("$currentDir/sample-workspace already exists, aborting scaffolding")
            Files.createDirectory(sampleDirectory.toPath()).toFile()
        }
    }

    private fun scaffoldWorkspace(directory: File) {
        val indexFile = File("$directory/index.js")
        indexFile.writeText(sampleIndexJs)
    }

    override fun call(): Int {
        val dir = getWorkspaceDirectory()
        scaffoldWorkspace(dir)

        PrintUtils.message("Scaffolded workspace in ${dir.path}")

        return CloudInteractor(
            client = ApiClient(apiUrl),
        ).deployMaestroMockServerWorkspace(
            workspace = dir,
            apiKey = apiKey,
        )
    }

    companion object {
        val sampleIndexJs = """
            get('/posts', (req, res, session) => {
                session.count = (session.count || 0) + 1;
        
                res.json({
                    posts: [{ id: 0, title: "Post 1" }, {id: 1, title: "Post 2"}],
                    count: session.count
                });
            });
        """.trimIndent()
    }
}