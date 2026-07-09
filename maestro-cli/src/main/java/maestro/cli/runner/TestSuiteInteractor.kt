package maestro.cli.runner

import kotlinx.coroutines.channels.Channel
import maestro.Maestro
import maestro.DeviceConnectionException
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
import maestro.orchestra.debug.FlowDebugOutput
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import maestro.orchestra.yaml.YamlCommandReader
import okio.Sink
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.measureTimeMillis
import kotlin.time.Duration.Companion.seconds
import maestro.cli.util.ScreenshotUtils
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withInjectedShellEnvVars

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
    private val captureFullArtifacts: Boolean = false,
) {

    private val logger = LoggerFactory.getLogger(TestSuiteInteractor::class.java)
    private val shardPrefix = shardIndex?.let { "[shard ${it + 1}] " }.orEmpty()

    suspend fun runTestSuite(
        executionPlan: WorkspaceExecutionPlanner.ExecutionPlan,
        reportOut: Sink?,
        env: Map<String, String>,
        debugOutputPath: Path,
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
            val (result, aiOutput) = runFlow(flowFile, updatedEnv, maestro, debugOutputPath)
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
            val (result, aiOutput) = runFlow(flowFile, updatedEnv, maestro, debugOutputPath)
            aiOutputs.add(aiOutput)

            if (result.status == FlowStatus.ERROR) {
                passed = false
            }
            flowResults.add(result)
        }


        val summary = buildSummary(flowResults, aiOutputs, passed, debugOutputPath)

        if (reportOut != null) {
            reporter.report(summary, reportOut)
        }

        return summary
    }

    /**
     * Consumes flows from [flowQueue] one at a time until [pending] reaches zero or [cancelled] is set.
     *
     * Used by [maestro.cli.runner.DynamicShardScheduler] for work-stealing execution. Each call to
     * [runFlow] is a complete flow execution; if [runFlow] throws (session-level crash), [onDeviceCrash]
     * is invoked to re-enqueue the flow before this worker exits.
     */
    suspend fun runFromQueue(
        flowQueue: Channel<Path>,
        pending: AtomicInteger,
        cancelled: AtomicBoolean,
        onDeviceCrash: (Path) -> Unit,
        env: Map<String, String>,
        debugOutputPath: Path,
        deviceId: String?,
    ): TestExecutionSummary {
        val flowResults = mutableListOf<TestExecutionSummary.FlowResult>()
        val aiOutputs = mutableListOf<FlowAIOutput>()
        var passed = true

        while (pending.get() > 0 && !cancelled.get()) {
            val flowPath = flowQueue.tryReceive().getOrNull() ?: run {
                // Queue temporarily empty: a re-enqueue may be in-flight; spin briefly.
                kotlinx.coroutines.delay(50)
                continue
            }

            var completed = false
            try {
                val flowFile = flowPath.toFile()
                val updatedEnv = env
                    .withInjectedShellEnvVars()
                    .withDefaultEnvVars(flowFile, deviceId, shardIndex)

                val (result, aiOutput) = runFlow(flowFile, updatedEnv, maestro, debugOutputPath, rethrowTransportDeath = true)
                flowResults.add(result)
                aiOutputs.add(aiOutput)
                if (result.status == FlowStatus.ERROR) passed = false
                completed = true
            } catch (e: Exception) {
                // Session-level crash (device disconnected mid-flow): re-enqueue and stop this worker.
                logger.error("${shardPrefix}Session crashed on flow ${flowPath.fileName}: ${e.message}")
                onDeviceCrash(flowPath)
                return buildSummary(flowResults, aiOutputs, passed = false, debugOutputPath = debugOutputPath)
            } finally {
                if (completed) pending.decrementAndGet()
            }
        }

        return buildSummary(flowResults, aiOutputs, passed, debugOutputPath)
    }

    private fun buildSummary(
        flowResults: List<TestExecutionSummary.FlowResult>,
        aiOutputs: List<FlowAIOutput>,
        passed: Boolean,
        debugOutputPath: Path,
    ): TestExecutionSummary {
        val suiteDuration = flowResults.sumOf { it.duration?.inWholeSeconds ?: 0 }.seconds

        TestSuiteStatusView.showSuiteResult(
            TestSuiteViewModel(
                status = if (passed) FlowStatus.SUCCESS else FlowStatus.ERROR,
                duration = suiteDuration,
                shardIndex = shardIndex,
                flows = flowResults.map {
                    TestSuiteViewModel.FlowResult(
                        name = it.name,
                        status = it.status,
                        duration = it.duration,
                    )
                },
            ),
            uploadUrl = ""
        )

        TestDebugReporter.saveSuggestions(aiOutputs, debugOutputPath)

        return TestExecutionSummary(
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
            totalTests = flowResults.size,
        )
    }

    private suspend fun runFlow(
        flowFile: File,
        env: Map<String, String>,
        maestro: Maestro,
        debugOutputPath: Path,
        // When true, a transport death (device/connection dying mid-flow) is rethrown instead of being
        // recorded as a flow ERROR. The dynamic scheduler's worker uses this so it can re-enqueue the
        // flow onto a healthy device via onDeviceCrash; the static path leaves it false and keeps the
        // pre-existing behavior of reporting the flow as failed and moving on.
        rethrowTransportDeath: Boolean = false,
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

        val launchedAppId = commands.firstNotNullOfOrNull { it.launchAppCommand?.appId }
        val maestroConfig = YamlCommandReader.getConfig(commands)
        val flowName: String = maestroConfig?.name ?: flowFile.nameWithoutExtension

        logger.info("$shardPrefix Running flow $flowName")

        // Per-flow folder ArtifactsGenerator writes the bundle into (see BundleLayout).
        val flowDir = TestDebugReporter.createFlowDir(debugOutputPath, flowName, shardIndex)

        var debugOutput = FlowDebugOutput()
        val flowTimeMillis = measureTimeMillis {
            try {
                val orchestra = Orchestra(
                    maestro = maestro,
                    artifactsDir = flowDir,
                    captureFullArtifacts = captureFullArtifacts,
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

                val result = orchestra.runFlow(commands)
                flowStatus = if (result.success) FlowStatus.SUCCESS else FlowStatus.ERROR
                debugOutput = result.debugOutput
            } catch (e: Exception) {
                // A transport death is infrastructure, not a test failure. When the caller opted in
                // (dynamic scheduler), rethrow so the flow can be re-enqueued onto a healthy device
                // instead of being buried as a flow ERROR — which is what let a dead device cascade
                // through every remaining flow on that shard.
                if (rethrowTransportDeath && e is DeviceConnectionException) throw e
                logger.error("${shardPrefix}Failed to complete flow", e)
                flowStatus = FlowStatus.ERROR
                errorMessage = ErrorViewUtils.exceptionToMessage(e)
            } finally {
                // Stop the app to free device memory before the next flow runs.
                // App ID is resolved from the flow's own launchApp command — no extra config needed.
                launchedAppId?.let { appId ->
                    runCatching { maestro.stopApp(appId) }
                        .onFailure { e -> logger.debug("${shardPrefix}stopApp($appId) skipped: ${e.message}") }
                }
            }
        }
        val flowDuration = TimeUtils.durationInSeconds(flowTimeMillis)
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
