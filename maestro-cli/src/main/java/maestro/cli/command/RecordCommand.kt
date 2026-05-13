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

package maestro.cli.command

import kotlinx.coroutines.runBlocking
import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.AppleTeamIdMixin
import maestro.cli.ConfigFileMixin
import maestro.cli.DebugOutputMixin
import maestro.cli.DeviceSelectionMixin
import maestro.cli.DisableAnsiMixin
import maestro.cli.EnvMixin
import maestro.cli.ShowHelpMixin
import maestro.cli.analytics.Analytics
import maestro.cli.analytics.RecordFinishedEvent
import maestro.cli.analytics.RecordStartedEvent
import maestro.cli.graphics.LocalVideoRenderer
import maestro.cli.graphics.RemoteVideoRenderer
import maestro.cli.graphics.SkiaFrameRenderer
import maestro.cli.report.TestDebugReporter
import maestro.cli.runner.TestRunner
import maestro.cli.runner.resultview.AnsiResultView
import maestro.cli.session.MaestroSessionManager
import maestro.cli.util.FileUtils.isWebFlow
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import okio.sink
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable

@CommandLine.Command(
    name = "record",
    description = [
        "Render a beautiful video of your Flow - Great for demos and bug reports"
    ]
)
class RecordCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.Mixin
    var deviceSelection = DeviceSelectionMixin()

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Parameters(index = "0", description = ["The Flow file to record."])
    private lateinit var flowFile: File

    @CommandLine.Parameters(description = ["Output file for the rendered video. Only valid for local rendering (--local)."], arity = "0..1", index = "1")
    private var outputFile: File? = null

    @CommandLine.Mixin
    var configFileMixin = ConfigFileMixin()

    @Option(names = ["--local"], description = ["(Beta) Record using local rendering. This will become the default in a future Maestro release."])
    private var local: Boolean = false

    @CommandLine.Mixin
    var envMixin = EnvMixin()

    @CommandLine.Mixin
    var appleTeamIdMixin = AppleTeamIdMixin()

    @CommandLine.Mixin
    var debugOutputMixin = DebugOutputMixin()

    @CommandLine.Spec
    lateinit var commandSpec: CommandLine.Model.CommandSpec

    override fun call(): Int {
        // Track record start
        val startTime = System.currentTimeMillis()
        val platform = parent?.platform ?: "unknown"
        Analytics.trackEvent(RecordStartedEvent(platform = platform))

        if (!flowFile.exists()) {
            throw CommandLine.ParameterException(
                commandSpec.commandLine(),
                "File not found: $flowFile"
            )
        }

        if (!local && outputFile != null) {
            throw CommandLine.ParameterException(
                commandSpec.commandLine(),
                "The outputFile parameter is only valid for local rendering (--local).",
            )
        }

        if (configFileMixin.configFile != null && configFileMixin.configFile?.exists()?.not() == true) {
            throw CliError("The config file ${configFileMixin.configFile?.absolutePath} does not exist.")
        }
        TestDebugReporter.install(debugOutputPathAsString = debugOutputMixin.debugOutput, printToConsole = parent?.verbose == true)
        val path = TestDebugReporter.getDebugOutputPath()

        val deviceId = if (flowFile.isWebFlow()) {
            throw CliError("'record' command does not support web flows yet.")
        } else {
            deviceSelection.deviceId ?: parent?.deviceId
        }

        val plan = WorkspaceExecutionPlanner.plan(
            input = setOf(flowFile.toPath()),
            includeTags = emptyList(),
            excludeTags = emptyList(),
            config = configFileMixin.configFile?.toPath()
        )

        return MaestroSessionManager.newSession(
            host = parent?.host,
            port = parent?.port,
            driverHostPort = deviceSelection.driverHostPort ?: parent?.driverHostPort,
            deviceId = deviceId,
            teamId = appleTeamIdMixin.appleTeamId,
            platform = deviceSelection.platform ?: parent?.platform,
            executionPlan = plan,
            block = { session ->
                val maestro = session.maestro
                val device = session.device

                if (flowFile.isDirectory) {
                    throw CommandLine.ParameterException(
                        commandSpec.commandLine(),
                        "Only single Flows are supported by \"maestro record\". $flowFile is a directory.",
                    )
                }

                val resultView = AnsiResultView()
                val screenRecording = kotlin.io.path.createTempFile(suffix = ".mp4").toFile()
                val exitCode = screenRecording.sink().use { out ->
                    runBlocking { maestro.startScreenRecording(out) }.use {
                        TestRunner.runSingle(
                            maestro,
                            device,
                            flowFile,
                            envMixin.env,
                            resultView,
                            path,
                            testOutputDir = null,
                            deviceId = parent?.deviceId,
                        )
                    }
                }

                val frames = resultView.getFrames()

                val localOutputFile = outputFile ?: path.resolve("maestro-recording.mp4").toFile()
                val videoRenderer = if (local) LocalVideoRenderer(
                    frameRenderer = SkiaFrameRenderer(),
                    outputFile = localOutputFile,
                    outputFPS = 25,
                    outputWidthPx = 1920,
                    outputHeightPx = 1080,
                ) else RemoteVideoRenderer()
                videoRenderer.render(screenRecording, frames)

                TestDebugReporter.deleteOldFiles()

                // Track record completion
                val duration = System.currentTimeMillis() - startTime
                Analytics.trackEvent(RecordFinishedEvent(platform = platform, durationMs = duration))
                Analytics.flush()

                exitCode
            },
        )
    }
}
