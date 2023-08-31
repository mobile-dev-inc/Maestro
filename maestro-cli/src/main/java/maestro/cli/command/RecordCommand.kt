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

import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.DisableAnsiMixin
import maestro.cli.api.ApiClient
import maestro.cli.report.TestDebugReporter
import maestro.cli.runner.TestRunner
import maestro.cli.runner.resultview.AnsiResultView
import maestro.cli.session.MaestroSessionManager
import maestro.cli.view.ProgressBar
import okio.sink
import org.fusesource.jansi.Ansi
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

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Parameters
    private lateinit var flowFile: File

    @Option(names = ["-e", "--env"])
    private var env: Map<String, String> = emptyMap()

    @CommandLine.Spec
    lateinit var commandSpec: CommandLine.Model.CommandSpec

    @Option(
        names = ["--debug-output"],
        description = ["Configures the debug output in this path, instead of default"]
    )
    private var debugOutput: String? = null

    override fun call(): Int {
        if (!flowFile.exists()) {
            throw CommandLine.ParameterException(
                commandSpec.commandLine(),
                "File not found: $flowFile"
            )
        }

        if (parent?.platform != null) {
            throw CliError("--platform option was deprecated. You can remove it to run your test.")
        }


        TestDebugReporter.install(debugOutputPathAsString = debugOutput)
        val path = TestDebugReporter.getDebugOutputPath()

        return MaestroSessionManager.newSession(parent?.host, parent?.port, parent?.deviceId) { session ->
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
                maestro.startScreenRecording(out).use {
                    TestRunner.runSingle(maestro, device, flowFile, env, resultView, path)
                }
            }

            System.err.println()
            System.err.println("@|bold Rendering your video. This usually takes a couple minutes...|@".render())
            System.err.println()

            val frames = resultView.getFrames()
            val client = ApiClient("")

            val uploadProgress = ProgressBar(50)
            System.err.println("Uploading raw files for render...")
            val id = client.render(screenRecording, frames) { totalBytes, bytesWritten ->
                uploadProgress.set(bytesWritten.toFloat() / totalBytes)
            }
            System.err.println()

            var renderProgress: ProgressBar? = null
            var status: String? = null
            var positionInQueue: Int? = null
            while (true) {
                val state = client.getRenderState(id)

                // If new position or status, print header
                if (state.status != status || state.positionInQueue != positionInQueue) {
                    status = state.status
                    positionInQueue = state.positionInQueue

                    if (renderProgress != null) {
                        renderProgress.set(1f)
                        System.err.println()
                    }

                    System.err.println()

                    System.err.println("Status : ${styledStatus(state.status)}")
                    if (state.positionInQueue != null) {
                        System.err.println("Position In Queue : ${state.positionInQueue}")
                    }
                }

                // Add ticks to progress bar
                if (state.currentTaskProgress != null) {
                    if (renderProgress == null) renderProgress = ProgressBar(50)
                    renderProgress.set(state.currentTaskProgress)
                }

                // Print download url or error and return
                if (state.downloadUrl != null || state.error != null) {
                    System.err.println()
                    if (state.downloadUrl != null) {
                        System.err.println("@|bold Signed Download URL:|@".render())
                        System.err.println()
                        print("@|cyan,bold ${state.downloadUrl}|@".render())
                        System.err.println()
                        System.err.println()
                        System.err.println("Open the link above to download your video. If you're sharing on Twitter be sure to tag us @|bold @mobile__dev|@!".render())
                    } else {
                        System.err.println("@|bold Render encountered during rendering:|@".render())
                        System.err.println(state.error)
                    }
                    break
                }

                Thread.sleep(2000)
            }

            TestDebugReporter.deleteOldFiles()

            exitCode
        }
    }

    private fun styledStatus(status: String): String {
        val style = when (status) {
            "PENDING" -> "yellow,bold"
            "RENDERING" -> "blue,bold"
            "SUCCESS" -> "green,bold"
            else -> "bold"
        }
        return "@|$style $status|@".render()
    }

    private fun String.render(): String {
        return Ansi.ansi().render(this).toString()
    }
}
