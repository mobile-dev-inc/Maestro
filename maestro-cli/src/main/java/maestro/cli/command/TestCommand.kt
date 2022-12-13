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
import maestro.cli.report.ReportFormat
import maestro.cli.report.ReporterFactory
import maestro.cli.runner.resultview.AnsiResultView
import maestro.cli.runner.resultview.PlainTextResultView
import maestro.cli.runner.TestRunner
import maestro.cli.runner.TestSuiteInteractor
import maestro.cli.util.MaestroFactory
import okio.buffer
import okio.sink
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable
import kotlin.concurrent.thread

@CommandLine.Command(
    name = "test",
    description = [
        "Test a Flow or set of Flows on a local iOS Simulator or Android Emulator"
    ]
)
class TestCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Parameters
    private lateinit var flowFile: File

    @Option(names = ["-c", "--continuous"])
    private var continuous: Boolean = false

    @Option(names = ["-e", "--env"])
    private var env: Map<String, String> = emptyMap()

    @Option(names = ["--format"])
    private var format: ReportFormat = ReportFormat.NOOP

    @Option(names = ["--output"])
    private var output: File? = null

    @CommandLine.Spec
    lateinit var commandSpec: CommandLine.Model.CommandSpec

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

        val (maestro, device) = MaestroFactory.createMaestro(parent?.host, parent?.port, parent?.deviceId)

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            maestro.close()
        })

        return maestro.use {
            if (flowFile.isDirectory || format != ReportFormat.NOOP) {
                if (continuous) {
                    throw CommandLine.ParameterException(
                        commandSpec.commandLine(),
                        "Continuous mode is not supported for directories. $flowFile is a directory",
                    )
                }

                val suiteResult = TestSuiteInteractor(
                    maestro = maestro,
                    device = device,
                    reporter = ReporterFactory.buildReporter(format)
                ).runTestSuite(
                    input = flowFile,
                    env = env,
                    reportOut = format.fileExtension
                        ?.let { extension ->
                            (output ?: File("report$extension"))
                                .sink()
                                .buffer()
                        }
                )

                if (suiteResult.passed) {
                    0
                } else {
                    1
                }
            } else {
                if (continuous) {
                    TestRunner.runContinuous(maestro, device, flowFile, env)
                } else {
                    val resultView = if (DisableAnsiMixin.ansiEnabled) AnsiResultView() else PlainTextResultView()
                    TestRunner.runSingle(maestro, device, flowFile, env, resultView)
                }
            }
        }
    }
}
