package maestro.cli.runner

import maestro.Maestro
import maestro.MaestroException
import maestro.cli.CliError
import maestro.cli.device.Device
import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import maestro.cli.report.CommandDebugMetadata
import maestro.cli.report.FlowDebugMetadata
import maestro.cli.report.ScreenshotDebugMetadata
import maestro.cli.report.TestDebugReporter
import maestro.cli.report.TestSuiteReporter
import maestro.cli.util.PrintUtils
import maestro.cli.view.ErrorViewUtils
import maestro.cli.view.TestSuiteStatusView
import maestro.cli.view.TestSuiteStatusView.TestSuiteViewModel
import maestro.orchestra.Orchestra
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import maestro.orchestra.yaml.YamlCommandReader
import okio.Sink
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.math.roundToLong
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds

class TestSuiteInteractor(
    private val maestro: Maestro,
    private val device: Device? = null,
    private val reporter: TestSuiteReporter,
    private val includeTags: List<String> = emptyList(),
    private val excludeTags: List<String> = emptyList(),
) {

    private val logger = LoggerFactory.getLogger(TestSuiteInteractor::class.java)

    fun runTestSuite(
        input: File,
        reportOut: Sink?,
        env: Map<String, String>,
    ): TestExecutionSummary {
        return if (input.isFile) {
            runTestSuite(
                listOf(input),
                reportOut,
                env,
            )
        } else {
            val flowFiles = WorkspaceExecutionPlanner
                .plan(
                    input = input.toPath().toAbsolutePath(),
                    includeTags = includeTags,
                    excludeTags = excludeTags,
                )
                .flowsToRun

            if (flowFiles.isEmpty()) {
                throw CliError("No flow returned from the tag filter used")
            }

            runTestSuite(
                flowFiles.map { it.toFile() },
                reportOut,
                env,
            )
        }
    }

    private fun runTestSuite(
        flows: List<File>,
        reportOut: Sink?,
        env: Map<String, String>,
    ): TestExecutionSummary {
        val flowResults = mutableListOf<TestExecutionSummary.FlowResult>()

        PrintUtils.message("Waiting for flows to complete...")
        println()

        var passed = true
        flows.forEach { flowFile ->
            val result = runFlow(flowFile, env, maestro)

            if (result.status == FlowStatus.ERROR) {
                passed = false
            }

            // TODO accumulate extra information
            // - Command statuses
            // - View hierarchies
            flowResults.add(result)
        }

        val suiteDuration = flowResults.sumOf { it.duration?.inWholeSeconds ?: 0 }.seconds

        TestSuiteStatusView.showSuiteResult(
            TestSuiteViewModel(
                status = if (passed) FlowStatus.SUCCESS else FlowStatus.ERROR,
                duration = suiteDuration,
                flows = flowResults
                    .map {
                        TestSuiteViewModel.FlowResult(
                            name = it.name,
                            status = it.status,
                            duration = it.duration,
                        )
                    },
            )
        )

        val summary = TestExecutionSummary(
            passed = passed,
            deviceName = device?.description,
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = passed,
                    flows = flowResults,
                    duration = suiteDuration
                )
            )
        )

        if (reportOut != null) {
            reporter.report(
                summary,
                reportOut,
            )
        }

        return summary
    }

    private fun runFlow(
        flowFile: File,
        env: Map<String, String>,
        maestro: Maestro,
    ): TestExecutionSummary.FlowResult {
        var flowName: String = flowFile.nameWithoutExtension
        var flowStatus: FlowStatus
        var errorMessage: String? = null

        // debug
        val debug = FlowDebugMetadata()
        val debugCommands = debug.commands
        val debugScreenshots = debug.screenshots

        fun takeDebugScreenshot(status: CommandStatus): File? {
            val containsFailed = debugScreenshots.any { it.status == CommandStatus.FAILED }

            // Avoids duplicate failed images from parent commands
            if (containsFailed && status == CommandStatus.FAILED) {
                return null
            }

            val result = kotlin.runCatching {
                val out = File.createTempFile("screenshot-${System.currentTimeMillis()}", ".png")
                    .also { it.deleteOnExit() } // save to another dir before exiting
                maestro.takeScreenshot(out)
                debugScreenshots.add(
                    ScreenshotDebugMetadata(
                        screenshot = out,
                        timestamp = System.currentTimeMillis(),
                        status = status
                    )
                )
                out
            }

            return result.getOrNull()
        }

        val flowTimeMillis = measureTimeMillis {
            try {
                val commands = YamlCommandReader.readCommands(flowFile.toPath())
                    .withEnv(env)

                val config = YamlCommandReader.getConfig(commands)

                val orchestra = Orchestra(
                    maestro = maestro,
                    onCommandStart = { _, command ->
                        logger.info("${command.description()} RUNNING")
                        debugCommands[command] = CommandDebugMetadata(
                            timestamp = System.currentTimeMillis(),
                            status = CommandStatus.RUNNING
                        )
                    },
                    onCommandComplete = { _, command ->
                        logger.info("${command.description()} COMPLETED")
                        debugCommands[command]?.let {
                            it.status = CommandStatus.COMPLETED
                            it.calculateDuration()
                        }
                    },
                    onCommandFailed = { _, command, e ->
                        logger.info("${command.description()} FAILED")
                        if (e is MaestroException) debug.exception = e
                        debugCommands[command]?.let {
                            it.status = CommandStatus.FAILED
                            it.calculateDuration()
                            it.error = e
                        }

                        takeDebugScreenshot(CommandStatus.FAILED)
                        Orchestra.ErrorResolution.FAIL
                    },
                    onCommandSkipped = { _, command ->
                        logger.info("${command.description()} SKIPPED")
                        debugCommands[command]?.let {
                            it.status = CommandStatus.SKIPPED
                        }
                    },
                    onCommandReset = { command ->
                        logger.info("${command.description()} PENDING")
                        debugCommands[command]?.let {
                            it.status = CommandStatus.PENDING
                        }
                    },
                )

                config?.name?.let {
                    flowName = it
                }

                val flowSuccess = orchestra.runFlow(commands)
                flowStatus = if (flowSuccess) FlowStatus.SUCCESS else FlowStatus.ERROR
            } catch (e: Exception) {
                logger.error("Failed to complete flow", e)
                flowStatus = FlowStatus.ERROR
                errorMessage = ErrorViewUtils.exceptionToMessage(e)
            }
        }
        val flowDuration = (flowTimeMillis / 1000f).roundToLong().seconds

        TestDebugReporter.saveFlow(flowName, debug)

        TestSuiteStatusView.showFlowCompletion(
            TestSuiteViewModel.FlowResult(
                name = flowName,
                status = flowStatus,
                duration = flowDuration,
                error = debug.exception?.message,
            )
        )

        return TestExecutionSummary.FlowResult(
            name = flowName,
            fileName = flowFile.nameWithoutExtension,
            status = flowStatus,
            failure = if (flowStatus == FlowStatus.ERROR) {
                TestExecutionSummary.Failure(
                    message = errorMessage ?: debug.exception?.message ?: "Unknown error",
                )
            } else null,
            duration = flowDuration,
        )
    }

}
