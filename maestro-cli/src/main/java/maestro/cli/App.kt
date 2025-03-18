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

import maestro.MaestroException
import maestro.cli.analytics.Analytics
import maestro.cli.command.BugReportCommand
 import maestro.cli.command.ChatCommand
import maestro.cli.command.CloudCommand
import maestro.cli.command.DownloadSamplesCommand
import maestro.cli.command.LoginCommand
import maestro.cli.command.LogoutCommand
import maestro.cli.command.PrintHierarchyCommand
import maestro.cli.command.QueryCommand
import maestro.cli.command.RecordCommand
import maestro.cli.command.StartDeviceCommand
import maestro.cli.command.StudioCommand
import maestro.cli.command.TestCommand
import maestro.cli.command.UploadCommand
import maestro.cli.insights.TestAnalysisManager
import maestro.cli.update.Updates
import maestro.cli.util.ChangeLogUtils
import maestro.cli.util.ErrorReporter
import maestro.cli.view.box
import maestro.debuglog.DebugLogStore
import picocli.AutoComplete.GenerateCompletion
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
        StartDeviceCommand::class,
        GenerateCompletion::class,
        ChatCommand::class,
    ]
)
class App {
    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @Option(names = ["-v", "--version"], versionHelp = true, description = ["Display CLI version"])
    var requestedVersion: Boolean? = false

    @Option(names = ["-p", "--platform"], description = ["(Optional) Select a platform to run on"])
    var platform: String? = null

    @Option(names = ["--host"], hidden = true)
    var host: String? = null

    @Option(names = ["--port"], hidden = true)
    var port: Int? = null

    @Option(
        names = ["--device", "--udid"],
        description = ["(Optional) Device ID to run on explicitly, can be a comma separated list of IDs: --device \"Emulator_1,Emulator_2\" "],
    )
    var deviceId: String? = null

    @Option(names = ["--verbose"], description = ["Enable verbose logging"])
    var verbose: Boolean = false
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

    Analytics.maybeMigrate()
    Analytics.maybeAskToEnableAnalytics()

    Dependencies.install()
    Updates.fetchUpdatesAsync()
    Analytics.maybeUploadAnalyticsAsync()

    val commandLine = CommandLine(App())
        .setUsageHelpWidth(160)
        .setCaseInsensitiveEnumValuesAllowed(true)
        .setExecutionStrategy(DisableAnsiMixin::executionStrategy)
        .setExecutionExceptionHandler { ex, cmd, cmdParseResult ->
            runCatching { ErrorReporter.report(ex, cmdParseResult) }

            // make errors red
            cmd.colorScheme = CommandLine.Help.ColorScheme.Builder()
                .errors(CommandLine.Help.Ansi.Style.fg_red)
                .build()

            cmd.err.println(
                cmd.colorScheme.errorText(ex.message.orEmpty())
            )

            if (ex !is CliError && ex !is MaestroException.UnsupportedJavaVersion) {
                cmd.err.println("\nThe stack trace was:")
                cmd.err.println(ex.stackTraceToString())
            }

            1
        }

    val generateCompletionCommand = commandLine.subcommands["generate-completion"]
    generateCompletionCommand?.commandSpec?.usageMessage()?.hidden(true)

    val exitCode = commandLine
        .execute(*args)

    DebugLogStore.finalizeRun()
    TestAnalysisManager.maybeNotify()

    val newVersion = Updates.checkForUpdates()
    if (newVersion != null) {
        Updates.fetchChangelogAsync()
        System.err.println()
        val changelog = Updates.getChangelog()
        val anchor = newVersion.toString().replace(".", "")
        System.err.println(
            listOf(
                "A new version of the Maestro CLI is available ($newVersion).\n",
                "See what's new:",
                "https://github.com/mobile-dev-inc/maestro/blob/main/CHANGELOG.md#$anchor",
                ChangeLogUtils.print(changelog),
                "Upgrade command:",
                "curl -Ls \"https://get.maestro.mobile.dev\" | bash",
            ).joinToString("\n").box()
        )
    }

    if (commandLine.isVersionHelpRequested) {
        printVersion()
        exitProcess(0)
    }

    exitProcess(exitCode)
}
