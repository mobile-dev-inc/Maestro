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

import maestro.cli.command.*
import maestro.cli.command.DownloadSamplesCommand
import maestro.cli.command.LogoutCommand
import maestro.cli.report.TestDebugReporter
import maestro.cli.update.Updates
import maestro.cli.util.ErrorReporter
import maestro.cli.view.box
import maestro.debuglog.DebugLogStore
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.util.*
import kotlin.system.exitProcess

@Command(
    name = "maestro",
    subcommands = [
        TestCommand::class,
        CloudCommand::class,
        RecordCommand::class,
        UploadCommand::class,
        PrintHierarchyCommand::class,
        QueryCommand::class,
        DownloadSamplesCommand::class,
        LoginCommand::class,
        LogoutCommand::class,
        BugReportCommand::class,
        StudioCommand::class,
        StartDeviceCommand::class
    ]
)
class App {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @Option(names = ["-v", "--version"], versionHelp = true)
    var requestedVersion: Boolean? = false

    @Option(names = ["-p", "--platform"], hidden = true)
    var platform: String? = null

    @Option(names = ["--host"], hidden = true)
    var host: String? = null

    @Option(names = ["--port"], hidden = true)
    var port: Int? = null

    @Option(names = ["--device", "--udid"], description = ["(Optional) Select a device to run on explicitly"])
    var deviceId: String? = null
}

private fun printVersion() {
    val props = App::class.java.classLoader.getResourceAsStream("version.properties").use {
        Properties().apply { load(it) }
    }

    println(props["version"])
}

fun main(args: Array<String>) {
    // Disable icon in Mac dock
    // https://stackoverflow.com/a/17544259
    System.setProperty("apple.awt.UIElement", "true")

    Dependencies.install()
    Updates.fetchUpdatesAsync()

    val commandLine = CommandLine(App())
        .setUsageHelpWidth(160)
        .setCaseInsensitiveEnumValuesAllowed(true)
        .setExecutionStrategy(DisableAnsiMixin::executionStrategy)
        .setExecutionExceptionHandler { ex, cmd, cmdParseResult ->
            runCatching { ErrorReporter.report(ex, cmdParseResult) }

            val message = if (ex is CliError) {
                ex.message
            } else {
                ex.stackTraceToString()
            }

            println()

            // make errors red
            cmd.colorScheme =  CommandLine.Help.ColorScheme.Builder()
                .errors(CommandLine.Help.Ansi.Style.fg_red)
                .build()

            cmd.err.println(
                cmd.colorScheme.errorText(message)
            )

            1
        }

    val exitCode = commandLine
        .execute(*args)

    TestDebugReporter.deleteOldFiles()
    DebugLogStore.finalizeRun()

    val newVersion = Updates.checkForUpdates()
    if (newVersion != null) {
        System.err.println()
        System.err.println(
            ("A new version of the Maestro CLI is available ($newVersion). Upgrade command:\n" +
                    "curl -Ls \"https://get.maestro.mobile.dev\" | bash").box()
        )
    }

    if (commandLine.isVersionHelpRequested) {
        printVersion()
        exitProcess(0)
    }

    exitProcess(exitCode)
}
