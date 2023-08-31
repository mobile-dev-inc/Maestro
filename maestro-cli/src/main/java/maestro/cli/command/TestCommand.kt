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
import maestro.cli.report.TestDebugReporter
import maestro.cli.runner.TestRunner
import maestro.cli.runner.TestSuiteInteractor
import maestro.cli.runner.resultview.AnsiResultView
import maestro.cli.runner.resultview.PlainTextResultView
import maestro.cli.session.MaestroSessionManager
import maestro.cli.util.PrintUtils
import maestro.orchestra.error.ValidationError
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import maestro.orchestra.yaml.YamlCommandReader
import okio.buffer
import okio.sink
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.File
import java.util.concurrent.Callable
import kotlin.io.path.absolutePathString

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

    @Option(
        names = ["--format"],
        description = ["Test report format (default=\${DEFAULT-VALUE}): \${COMPLETION-CANDIDATES}"],
    )
    private var format: ReportFormat = ReportFormat.NOOP

    @Option(
        names = ["--test-suite-name"],
        description = ["Test suite name"],
    )
    private var testSuiteName: String? = null

    @Option(names = ["--output"])
    private var output: File? = null

    @Option(
        names = ["--debug-output"],
        description = ["Configures the debug output in this path, instead of default"]
    )
    private var debugOutput: String? = null

    @Option(
        names = ["--include-tags"],
        description = ["List of tags that will remove the Flows that does not have the provided tags"],
        split = ",",
    )
    private var includeTags: List<String> = emptyList()

    @Option(
        names = ["--exclude-tags"],
        description = ["List of tags that will remove the Flows containing the provided tags"],
        split = ",",
    )
    private var excludeTags: List<String> = emptyList()

    @CommandLine.Spec
    lateinit var commandSpec: CommandLine.Model.CommandSpec

    private fun isWebFlow(): Boolean {
        if (!flowFile.isDirectory) {
            val config = YamlCommandReader.readConfig(flowFile.toPath())
            return Regex("http(s?)://").containsMatchIn(config.appId)
        }

        return false
    }

    override fun call(): Int {
        if (parent?.platform != null) {
            throw CliError("--platform option was deprecated. You can remove it to run your test.")
        }

        val executionPlan = try {
            WorkspaceExecutionPlanner.plan(flowFile.toPath().toAbsolutePath(), includeTags, excludeTags)
        } catch (e: ValidationError) {
            throw CliError(e.message)
        }

        val deviceId =
            if (isWebFlow()) "chromium".also { PrintUtils.warn("Web support is an experimental feature and may be removed in future versions.\n") }
            else parent?.deviceId

        env = env.withInjectedShellEnvVars()

        TestDebugReporter.install(debugOutputPathAsString = debugOutput)
        val debugOutputPath = TestDebugReporter.getDebugOutputPath()
        
        return MaestroSessionManager.newSession(parent?.host, parent?.port, deviceId) { session ->
            val maestro = session.maestro
            val device = session.device

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
                    reporter = ReporterFactory.buildReporter(format, testSuiteName),
                ).runTestSuite(
                    executionPlan = executionPlan,
                    env = env,
                    reportOut = format.fileExtension
                        ?.let { extension ->
                            (output ?: File("report$extension"))
                                .sink()
                                .buffer()
                        },
                    debugOutputPath = debugOutputPath
                )

                TestDebugReporter.deleteOldFiles()
                if (suiteResult.passed) {
                    0
                } else {
                    printExitDebugMessage()
                    1
                }
            } else {
                if (continuous) {
                    TestDebugReporter.deleteOldFiles()
                    TestRunner.runContinuous(maestro, device, flowFile, env)
                } else {
                    val resultView = if (DisableAnsiMixin.ansiEnabled) AnsiResultView() else PlainTextResultView()
                    val resultSingle = TestRunner.runSingle(maestro, device, flowFile, env, resultView, debugOutputPath)
                    if (resultSingle == 1) {
                        printExitDebugMessage()
                    }
                    TestDebugReporter.deleteOldFiles()
                    return@newSession resultSingle
                }
            }
        }
    }

    private fun printExitDebugMessage() {
        println()
        println("==== Debug output (logs & screenshots) ====")
        PrintUtils.message(TestDebugReporter.getDebugOutputPath().absolutePathString())
    }
}
