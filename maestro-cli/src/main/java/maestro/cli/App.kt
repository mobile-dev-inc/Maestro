/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.cli

import maestro.cli.command.PrintHierarchyCommand
import maestro.cli.command.QueryCommand
import maestro.cli.command.TestCommand
import maestro.cli.command.UploadCommand
import org.fusesource.jansi.AnsiConsole
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.util.Properties
import kotlin.system.exitProcess

@Command(
    name = "maestro",
    subcommands = [
        TestCommand::class,
        UploadCommand::class,
        PrintHierarchyCommand::class,
        QueryCommand::class,
    ]
)
class App {

    @Option(names = ["-v", "--version"], versionHelp = true)
    var requestedVersion: Boolean? = false

    @Option(names = ["-p", "--platform"])
    var platform: String? = null

    @Option(names = ["--host"])
    var host: String = "localhost"

    @Option(names = ["--port"])
    var port: Int? = null

    companion object {
        init {
            AnsiConsole.systemInstall()
        }
    }
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
            val message = if (ex is CliError) {
                ex.message
            } else {
                ex.stackTraceToString()
            }
            cmd.err.println(
                cmd.colorScheme.errorText(message)
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
