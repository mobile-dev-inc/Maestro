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
import maestro.cli.analytics.CliCommandRunEvent
import maestro.cli.analytics.CommandArgsSanitizer
import maestro.cli.command.BugReportCommand
import maestro.cli.command.ChatCommand
import maestro.cli.command.CheckSyntaxCommand
import maestro.cli.command.CloudCommand
import maestro.cli.command.DownloadSamplesCommand
import maestro.cli.command.DriverCommand
import maestro.cli.command.ListCloudDevicesCommand
import maestro.cli.command.ListDevicesCommand
import maestro.cli.command.LoginCommand
import maestro.cli.command.LogoutCommand
import maestro.cli.command.McpCommand
import maestro.cli.command.PrintHierarchyCommand
import maestro.cli.command.QueryCommand
import maestro.cli.command.RecordCommand
import maestro.cli.command.StartDeviceCommand
import maestro.cli.command.StudioCommand
import maestro.cli.command.TestCommand
import maestro.cli.insights.TestAnalysisManager
import maestro.cli.mcp.claimMcpStdout
import maestro.cli.update.Updates
import maestro.cli.util.ChangeLogUtils
import maestro.cli.util.ErrorReporter
import maestro.cli.view.box
import maestro.debuglog.DebugLogStore
import maestro.debuglog.LogConfig
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
        PrintHierarchyCommand::class,
        QueryCommand::class,
        DownloadSamplesCommand::class,
        LoginCommand::class,
        LogoutCommand::class,
        BugReportCommand::class,
        StudioCommand::class,
        StartDeviceCommand::class,
        ListDevicesCommand::class,
        ListCloudDevicesCommand::class,
        GenerateCompletion::class,
        ChatCommand::class,
        CheckSyntaxCommand::class,
        DriverCommand::class,
        McpCommand::class,
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

    @Option(names = ["--driver-host-port"], hidden = true)
    var driverHostPort: Int? = null

    @Option(
        names = ["--device", "--udid"],
        description = ["(Optional) Device ID to run on explicitly, can be a comma separated list of IDs: --device \"Emulator_1,Emulator_2\" "],
    )
    var deviceId: String? = null

    @Option(names = ["--verbose"], description = ["Enable verbose logging"])
    var verbose: Boolean = false
}

/**
 * The name reported for a run: the top-level subcommand [commandLine] belongs to, e.g. "test", or
 * "maestro" when no subcommand was given, as in `maestro --help`.
 */
internal fun commandRunName(commandLine: CommandLine): String {
    var command = commandLine
    while (command.parent?.parent != null) command = command.parent
    return command.commandName
}

internal fun commandRunName(parseResult: CommandLine.ParseResult): String =
    commandRunName(parseResult.asCommandLineList().last())

private fun trackCommandRun(name: String) {
    // Analytics must never stop the command from running.
    runCatching { Analytics.trackEvent(CliCommandRunEvent(command = name)) }
}

private fun printVersion() {
    val props = App::class.java.classLoader.getResourceAsStream("version.properties").use {
        Properties().apply { load(it) }
    }

    println(props["version"])
}

fun main(args: Array<String>) {
    // Must run before any other init: analytics notices, dependency banners, and
    // kotlin-logging's first-load message will otherwise land on the MCP JSON-RPC
    // channel and break the handshake for strict clients like Claude Desktop.
    if (args.firstOrNull() == "mcp") claimMcpStdout()

    // Establish a consistent logging baseline for every command before any init
    // logging fires. Without this, commands that don't call TestDebugReporter.install()
    // fall back to log4j's default console-at-ERROR config and silently ignore the
    // global --verbose flag. Console-only here (no file) so lightweight commands don't
    // litter the state dir; commands that want a maestro.log still opt in via install().
    LogConfig.configure(logFileName = null, printToConsole = args.contains("--verbose"))

    // Capture a sanitized representation of the invocation as a super-property on every
    // PostHog event. Must run before any analytics event can fire.
    Analytics.commandString = CommandArgsSanitizer.sanitize(args)

    // Disable icon in Mac dock
    // https://stackoverflow.com/a/17544259
    try {
        System.setProperty("apple.awt.UIElement", "true")
        Analytics.warnAndEnableAnalyticsIfNotDisable()

        Dependencies.install()
        Updates.fetchUpdatesAsync()

        val commandLine = CommandLine(App())
            .setUsageHelpWidth(160)
            .setCaseInsensitiveEnumValuesAllowed(true)
            .setExecutionStrategy { parseResult ->
                // Tracked here, once picocli has parsed the arguments, so the event names the
                // subcommand rather than whatever came first (e.g. a global option like --device).
                trackCommandRun(commandRunName(parseResult))
                DisableAnsiMixin.executionStrategy(parseResult)
            }
            .setExecutionExceptionHandler { ex, cmd, cmdParseResult ->

                runCatching { ErrorReporter.report(ex, cmdParseResult) }

                // make errors red
                println()
                cmd.colorScheme = CommandLine.Help.ColorScheme.Builder()
                    .errors(CommandLine.Help.Ansi.Style.fg_red)
                    .build()

                cmd.err.println(
                    cmd.colorScheme.errorText(ex.message.orEmpty())
                )

                if (
                    ex !is CliError && ex !is MaestroException.UnsupportedJavaVersion
                    && ex !is MaestroException.MissingAppleTeamId && ex !is MaestroException.IOSDeviceDriverSetupException
                ) {
                    cmd.err.println("\nThe stack trace was:")
                    cmd.err.println(ex.stackTraceToString())
                }

                1
            }

        // Runs whose arguments don't parse are tracked too, under the command they got as far as.
        val parseErrorHandler = commandLine.parameterExceptionHandler
        commandLine.setParameterExceptionHandler { ex, exArgs ->
            trackCommandRun(commandRunName(ex.commandLine))
            parseErrorHandler.handleParseException(ex, exArgs)
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
            Analytics.close()
            exitProcess(0)
        }

        Analytics.close()
        exitProcess(exitCode)
    } catch (e: Throwable) {
        Analytics.close()
        throw e
    }
}
