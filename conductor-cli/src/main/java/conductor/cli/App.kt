package conductor.cli

import conductor.cli.command.PrintHierarchyCommand
import conductor.cli.command.TestCommand
import picocli.CommandLine
import picocli.CommandLine.Command
import kotlin.system.exitProcess

@Command(
    name = "conductor",
    subcommands = [
        TestCommand::class,
        PrintHierarchyCommand::class,
    ]
)
class App

@Suppress("SpreadOperator")
fun main(args: Array<String>) {
    val exitCode = CommandLine(App())
        .setExitCodeExceptionMapper { 1 }
        .execute(*args)
    exitProcess(exitCode)
}
