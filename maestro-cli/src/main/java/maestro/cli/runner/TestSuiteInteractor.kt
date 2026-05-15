package maestro.cli.runner

import maestro.Maestro
import maestro.cli.CliError
import maestro.device.Device
import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import maestro.cli.report.SingleScreenFlowAIOutput
import maestro.cli.report.FlowAIOutput
import maestro.cli.report.TestDebugReporter
import maestro.cli.report.TestSuiteReporter
import maestro.cli.util.FileUtils.toCwdRelativeOrAbsoluteString
import maestro.cli.util.PrintUtils
import maestro.cli.util.TimeUtils
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
import java.nio.file.Path
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds
import maestro.cli.util.ScreenshotUtils
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.utils.TempFileHandler

/**
 * Similar to [TestRunner], but:
 *  * can run many flows at once
 *  * does not support continuous mode
 *
 *  Does not care about sharding. It only has to know the index of the shard it's running it, for logging purposes.
 */
class TestSuiteInteractor(
    private val maestro: Maestro,
    private val device: Device? = null,
    private val reporter: TestSuiteReporter,
    private val shardIndex: Int? = null,
    private val captureSteps: Boolean = false,
) {

    private val logger = LoggerFactory.getLogger(TestSuiteInteractor::class.java)
    private val shardPrefix = shardIndex?.let { "[shard ${it + 1}] " }.orEmpty()

    suspend fun runTestSuite(
        executionPlan: WorkspaceExecutionPlanner.ExecutionPlan,
        reportOut: Sink?,
        env: Map<String, String>,
        debugOutputPath: Path,
        testOutputDir: Path? = null,
        deviceId: String? = null,
    ): TestExecutionSummary {
        if (executionPlan.flowsToRun.isEmpty() && executionPlan.sequence.flows.isEmpty()) {
            throw CliError("${shardPrefix}No flows returned from the tag filter used")
        }

        val flowResults = mutableListOf<TestExecutionSummary.FlowResult>()

        PrintUtils.message("${shardPrefix}Waiting for flows to complete...")

        var passed = true
        val aiOutputs = mutableListOf<FlowAIOutput>()

        // first run sequence of flows if present
        val flowSequence = executionPlan.sequence
        for (flow in flowSequence.flows) {
            val flowFile = flow.toFile()
            val updatedEnv = env
                .withInjectedShellEnvVars()
                .withDefaultEnvVars(flowFile, deviceId, shardIndex)
            val (result, aiOutput) = runFlow(flowFile, updatedEnv, maestro, debugOutputPath, testOutputDir)
            flowResults.add(result)
            aiOutputs.add(aiOutput)

            if (result.status == FlowStatus.ERROR) {
                passed = false
                if (executionPlan.sequence.continueOnFailure != true) {
                    PrintUtils.message("${shardPrefix}Flow ${result.name} failed and continueOnFailure is set to false, aborting running sequential Flows")
                    println()
                    break
                }
            }
        }

        // proceed to run all other Flows
        executionPlan.flowsToRun.forEach { flow ->
            val flowFile = flow.toFile()
            val updatedEnv = env
                .withInjectedShellEnvVars()
                .withDefaultEnvVars(flowFile, deviceId, shardIndex)
            val (result, aiOutput) = runFlow(flowFile, updatedEnv, maestro, debugOutputPath, testOutputDir)
            aiOutputs.add(aiOutput)

            if (result.status == FlowStatus.ERROR) {
                passed = false
            }
            flowResults.add(result)
        }


        val suiteDuration = flowResults.sumOf { it.duration?.inWholeSeconds ?: 0 }.seconds

        TestSuiteStatusView.showSuiteResult(
            TestSuiteViewModel(
                status = if (passed) FlowStatus.SUCCESS else FlowStatus.ERROR,
                duration = suiteDuration,
                shardIndex = shardIndex,
                flows = flowResults
                    .map {
                        TestSuiteViewModel.FlowResult(
                            name = it.name,
                            status = it.status,
                            duration = it.duration,
                        )
                    },
            ),
            uploadUrl = ""
        )

        val summary = TestExecutionSummary(
            passed = passed,
            suites = listOf(
                TestExecutionSummary.SuiteResult(
                    passed = passed,
                    flows = flowResults,
                    duration = suiteDuration,
                    deviceName = device?.description,
                )
            ),
            passedCount = flowResults.count { it.status == FlowStatus.SUCCESS },
            totalTests = flowResults.size
        )

        if (reportOut != null) {
            reporter.report(
                summary,
                reportOut,
            )
        }

        // TODO(bartekpacia): Should it also be saving to debugOutputPath?
        TestDebugReporter.saveSuggestions(aiOutputs, debugOutputPath)

        return summary
    }

    private suspend fun runFlow(
        flowFile: File,
        env: Map<String, String>,
        maestro: Maestro,
        debugOutputPath: Path,
        testOutputDir: Path? = null
    ): Pair<TestExecutionSummary.FlowResult, FlowAIOutput> {
        // TODO(bartekpacia): merge TestExecutionSummary with AI suggestions
        //  (i.e. consider them also part of the test output)
        //  See #1973

        var flowStatus: FlowStatus
        var errorMessage: String? = null

        val aiOutput = FlowAIOutput(
            flowName = flowFile.nameWithoutExtension,
            flowFile = flowFile,
        )
        val commands = YamlCommandReader
            .readCommands(flowFile.toPath())
            .withEnv(env)

        val maestroConfig = YamlCommandReader.getConfig(commands)
        val flowName: String = maestroConfig?.name ?: flowFile.nameWithoutExtension

        logger.info("$shardPrefix Running flow $flowName")

        // Per-flow staging directory. ArtifactsGenerator writes the canonical
        // bundle here (commands.json, maestro.log, screenshot-❌-*.png); then
        // copyToFlatLayout renames the files out into the session dir using
        // CLI's historic flat naming. TempFileHandler.close() in finally
        // recursively deletes it (and any other temp files this flow created).
        val tempFileHandler = TempFileHandler()
        val flowBundleDir = tempFileHandler
            .createTempDirectory("maestro-cli-${flowName.replace("/", "_")}-")
            .toPath()
        lateinit var orchestra: Orchestra

        val flowTimeMillis = measureTimeMillis {
            try {
                orchestra = Orchestra(
                    maestro = maestro,
                    screenshotsDir = testOutputDir?.resolve("screenshots"),
                    artifactsDir = flowBundleDir,
                    listeners = listOf(CliConsoleListener(shardPrefix)),
                    onCommandFailed = { _, _, _ -> Orchestra.ErrorResolution.FAIL },
                    onCommandGeneratedOutput = { command, defects, screenshot ->
                        logger.info("${shardPrefix}${command.description()} generated output")
                        val screenshotPath = ScreenshotUtils.writeAIscreenshot(screenshot)
                        aiOutput.screenOutputs.add(
                            SingleScreenFlowAIOutput(
                                screenshotPath = screenshotPath,
                                defects = defects,
                            )
                        )
                    },
                )

                val flowSuccess = orchestra.runFlow(commands)
                flowStatus = if (flowSuccess) FlowStatus.SUCCESS else FlowStatus.ERROR
            } catch (e: Exception) {
                logger.error("${shardPrefix}Failed to complete flow", e)
                flowStatus = FlowStatus.ERROR
                errorMessage = ErrorViewUtils.exceptionToMessage(e)
            }
        }
        val flowDuration = TimeUtils.durationInSeconds(flowTimeMillis)

        val debugOutput = orchestra.debugOutput
        try {
            TestDebugReporter.copyToFlatLayout(
                sourceDir = flowBundleDir,
                destDir = debugOutputPath,
                flowName = flowName,
                shardIndex = shardIndex,
            )
        } finally {
            tempFileHandler.close()
        }
        // FIXME(bartekpacia): Save AI output as well

        TestSuiteStatusView.showFlowCompletion(
            TestSuiteViewModel.FlowResult(
                name = flowName,
                status = flowStatus,
                duration = flowDuration,
                shardIndex = shardIndex,
                error = debugOutput.exception?.message,
            )
        )

        // Extract step information if captureSteps is enabled
        val steps = if (captureSteps) {
            debugOutput.commands.entries
                .sortedBy { it.value.sequenceNumber }
                .mapIndexed { index, (command, metadata) ->
                    val durationStr = when (val duration = metadata.duration) {
                        null -> "<1ms"
                        else -> if (duration >= 1000) {
                            "%.1fs".format(duration / 1000.0)
                        } else {
                            "${duration}ms"
                        }
                    }
                    val status = metadata.status?.toString() ?: "UNKNOWN"
                    // Use evaluated command for interpolated labels, fallback to original
                    val displayCommand = metadata.evaluatedCommand ?: command
                    TestExecutionSummary.StepResult(
                        description = "${index + 1}. ${displayCommand.description()}",
                        status = status,
                        duration = durationStr,
                    )
                }
        } else {
            emptyList()
        }

        return Pair(
            first = TestExecutionSummary.FlowResult(
                name = flowName,
                fileName = flowFile.nameWithoutExtension,
                filePath = flowFile.toPath().toCwdRelativeOrAbsoluteString(),
                status = flowStatus,
                failure = if (flowStatus == FlowStatus.ERROR) {
                    TestExecutionSummary.Failure(
                        message = shardPrefix + (errorMessage ?: debugOutput.exception?.message ?: "Unknown error"),
                    )
                } else null,
                duration = flowDuration,
                properties = maestroConfig?.properties,
                tags = maestroConfig?.tags,
                steps = steps,
            ),
            second = aiOutput,
        )
    }

}
