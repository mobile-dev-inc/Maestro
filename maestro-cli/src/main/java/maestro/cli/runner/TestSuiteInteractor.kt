package maestro.cli.runner

import maestro.Maestro
import maestro.MaestroException
import maestro.cli.CliError
import maestro.device.Device
import maestro.cli.model.FlowStatus
import maestro.cli.model.TestExecutionSummary
import maestro.cli.report.SingleScreenFlowAIOutput
import maestro.cli.report.CommandDebugMetadata
import maestro.cli.report.FlowAIOutput
import maestro.cli.report.FlowDebugOutput
import maestro.cli.report.TestDebugReporter
import maestro.cli.util.PrintUtils
import maestro.cli.util.TimeUtils
import maestro.cli.view.ErrorViewUtils
import maestro.cli.view.TestSuiteStatusView
import maestro.cli.view.TestSuiteStatusView.TestSuiteViewModel
import maestro.orchestra.Orchestra
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import maestro.orchestra.yaml.YamlCommandReader
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds
import maestro.cli.util.ScreenshotUtils
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import java.util.Locale

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
    private val shardIndex: Int? = null,
    private val captureSteps: Boolean = false,
) {

    private val logger = LoggerFactory.getLogger(TestSuiteInteractor::class.java)
    private val shardPrefix = shardIndex?.let { "[shard ${it + 1}] " }.orEmpty()

    suspend fun runTestSuite(
        executionPlan: WorkspaceExecutionPlanner.ExecutionPlan,
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

        val debugOutput = FlowDebugOutput()
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

        val flowStartTime = System.currentTimeMillis()
        var commandSequenceNumber = 0
        val artifactAttachments = mutableListOf<TestExecutionSummary.Attachment>()
        val flowTimeMillis = measureTimeMillis {
            try {
                val orchestra = Orchestra(
                    maestro = maestro,
                    screenshotsDir = testOutputDir?.resolve("screenshots"),
                    onCommandStart = { _, command ->
                        logger.info("${shardPrefix}${command.description()} RUNNING")
                        debugOutput.commands[command] = CommandDebugMetadata(
                            timestamp = System.currentTimeMillis(),
                            status = CommandStatus.RUNNING,
                            sequenceNumber = commandSequenceNumber++,
                        )
                    },
                    onCommandMetadataUpdate = { command, metadata ->
                        debugOutput.commands[command]?.evaluatedCommand = metadata.evaluatedCommand
                    },
                    onArtifactCreated = { artifact ->
                        artifactAttachments.add(
                            TestExecutionSummary.Attachment(
                                file = artifact.file,
                                label = artifact.label,
                                mimeType = artifact.mimeType,
                            ),
                        )
                    },
                    onCommandComplete = { _, command ->
                        logger.info("${shardPrefix}${command.description()} COMPLETED")
                        debugOutput.commands[command]?.let {
                            it.status = CommandStatus.COMPLETED
                            it.calculateDuration()
                        }
                    },
                    onCommandFailed = { _, command, e ->
                        logger.info("${shardPrefix}${command.description()} FAILED")
                        if (e is MaestroException) debugOutput.exception = e
                        debugOutput.commands[command]?.let {
                            it.status = CommandStatus.FAILED
                            it.calculateDuration()
                            it.error = e
                        }

                        ScreenshotUtils.takeDebugScreenshot(maestro, debugOutput, CommandStatus.FAILED)
                        Orchestra.ErrorResolution.FAIL
                    },
                    onCommandSkipped = { _, command ->
                        logger.info("${shardPrefix}${command.description()} SKIPPED")
                        debugOutput.commands[command]?.let {
                            it.status = CommandStatus.SKIPPED
                        }
                    },
                    onCommandWarned = { _, command ->
                        logger.info("${shardPrefix}${command.description()} WARNED")
                        debugOutput.commands[command]?.apply {
                            status = CommandStatus.WARNED
                        }
                    },
                    onCommandReset = { command ->
                        logger.info("${shardPrefix}${command.description()} PENDING")
                        debugOutput.commands[command]?.let {
                            it.status = CommandStatus.PENDING
                        }
                    },
                    onCommandGeneratedOutput = { command, defects, screenshot ->
                        logger.info("${shardPrefix}${command.description()} generated output")
                        val screenshotPath = ScreenshotUtils.writeAIscreenshot(screenshot)
                        aiOutput.screenOutputs.add(
                            SingleScreenFlowAIOutput(
                                screenshotPath = screenshotPath,
                                defects = defects,
                            )
                        )
                    }
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

        TestDebugReporter.saveFlow(
            flowName = flowName,
            debugOutput = debugOutput,
            shardIndex = shardIndex,
            path = debugOutputPath,
        )
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
                    val status = metadata.status?.toString() ?: "UNKNOWN"
                    // Use evaluated command for interpolated labels, fallback to original
                    val displayCommand = metadata.evaluatedCommand ?: command
                    TestExecutionSummary.StepResult(
                        description = "${index + 1}. ${displayCommand.description()}",
                        status = status,
                        durationMs = metadata.duration,
                        startTime = metadata.timestamp,
                    )
                }
        } else {
            emptyList()
        }
        val attachments = buildList {
            addAll(debugOutput.screenshots.map { screenshot ->
                TestExecutionSummary.Attachment(
                    file = screenshot.screenshot,
                    label = "screenshot-${screenshot.timestamp}",
                    mimeType = "image/png",
                )
            })
            addAll(artifactAttachments)
        }

        return Pair(
            first = TestExecutionSummary.FlowResult(
                name = flowName,
                fileName = flowFile.nameWithoutExtension,
                status = flowStatus,
                failure = if (flowStatus == FlowStatus.ERROR) {
                    TestExecutionSummary.Failure(
                        message = shardPrefix + (errorMessage ?: debugOutput.exception?.message ?: "Unknown error"),
                    )
                } else null,
                duration = flowDuration,
                startTime = flowStartTime,
                properties = maestroConfig?.properties,
                tags = maestroConfig?.tags,
                steps = steps,
                attachments = attachments,
                env = env,
            ),
            second = aiOutput,
        )
    }

}
