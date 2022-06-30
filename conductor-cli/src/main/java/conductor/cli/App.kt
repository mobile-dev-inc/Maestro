package conductor.cli

import conductor.cli.command.PrintHierarchyCommand
import conductor.cli.command.TestCommand
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.util.Properties
import kotlin.system.exitProcess

@Command(
    name = "conductor",
    subcommands = [
        TestCommand::class,
        PrintHierarchyCommand::class,
    ]
)
class App {

    @Option(names = ["-v", "--version"], versionHelp = true)
    var requestedVersion: Boolean? = false

}

private fun printVersion() {
    val props = App::class.java.classLoader.getResourceAsStream("version.properties").use {
        Properties().apply { load(it) }
    }

    println(props["version"])
}

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    val commandLine = CommandLine(App())
        .setExecutionExceptionHandler { ex, cmd, parseResult ->
            cmd.err.println(
                cmd.colorScheme.errorText(ex.message)
            )

            1
        }

    val exitCode = commandLine
        .execute(*args)

    if (commandLine.isVersionHelpRequested) {
        printVersion()
        exitProcess(0)
    }

    exitProcess(exitCode)
}
