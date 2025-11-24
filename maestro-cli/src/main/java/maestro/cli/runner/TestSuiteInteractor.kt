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
import maestro.cli.report.TestSuiteReporter
import maestro.cli.util.LogCapture
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
    private val captureLog: Boolean = false,
    private val logBufferSize: Int = 5000,
) {

    private val logger = LoggerFactory.getLogger(TestSuiteInteractor::class.java)
    private val shardPrefix = shardIndex?.let { "[shard ${it + 1}] " }.orEmpty()

    suspend fun runTestSuite(
        executionPlan: WorkspaceExecutionPlanner.ExecutionPlan,
        reportOut: Sink?,
        env: Map<String, String>,
        debugOutputPath: Path,
        testOutputDir: Path? = null
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
                .withDefaultEnvVars(flowFile)
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
                .withDefaultEnvVars(flowFile)
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

        val debugOutput = FlowDebugOutput()
        val aiOutput = FlowAIOutput(
            flowName = flowFile.nameWithoutExtension,
            flowFile = flowFile,
        )
        val commands = YamlCommandReader
            .readCommands(flowFile.toPath())
            .withEnv(env)

        var flowName: String = YamlCommandReader.getConfig(commands)?.name ?: flowFile.nameWithoutExtension

        logger.info("$shardPrefix Running flow $flowName")

        // Start log capture if enabled
        val logCapture = if (captureLog) {
            val deviceId = (device as? maestro.device.Device.Connected)?.instanceId
            LogCapture(deviceId = deviceId, bufferSize = logBufferSize).apply {
                try {
                    start()
                    logger.info("${shardPrefix}Started log capture")
                } catch (e: Exception) {
                    logger.warn("${shardPrefix}Failed to start log capture: ${e.message}")
                }
            }
        } else null

        // Track logs per command
        val commandLogs = mutableMapOf<String, MutableList<maestro.LogEntry>>()
        var lastLogCount = 0

        val flowTimeMillis = measureTimeMillis {
            try {
                YamlCommandReader.getConfig(commands)?.name?.let { flowName = it }

                val orchestra = Orchestra(
                    maestro = maestro,
                    screenshotsDir = testOutputDir?.resolve("screenshots"),
                    onCommandStart = { _, command ->
                        logger.info("${shardPrefix}${command.description()} RUNNING")
                        debugOutput.commands[command] = CommandDebugMetadata(
                            timestamp = System.currentTimeMillis(),
                            status = CommandStatus.RUNNING
                        )
                        // Mark log position at command start
                        if (logCapture != null) {
                            lastLogCount = logCapture.getBufferedLogs().size
                        }
                    },
                    onCommandComplete = { _, command ->
                        logger.info("${shardPrefix}${command.description()} COMPLETED")
                        debugOutput.commands[command]?.let {
                            it.status = CommandStatus.COMPLETED
                            it.calculateDuration()
                        }
                        // Capture logs generated during this command
                        if (logCapture != null) {
                            val currentLogs = logCapture.getBufferedLogs()
                            if (currentLogs.size > lastLogCount) {
                                val newLogs = currentLogs.drop(lastLogCount)
                                commandLogs.getOrPut(command.description()) { mutableListOf() }.addAll(newLogs)
                                lastLogCount = currentLogs.size
                            }
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
                        // Capture logs for failed command
                        if (logCapture != null) {
                            val currentLogs = logCapture.getBufferedLogs()
                            if (currentLogs.size > lastLogCount) {
                                val newLogs = currentLogs.drop(lastLogCount)
                                commandLogs.getOrPut(command.description()) { mutableListOf() }.addAll(newLogs)
                                lastLogCount = currentLogs.size
                            }
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
                        // Capture logs for warned command
                        if (logCapture != null) {
                            val currentLogs = logCapture.getBufferedLogs()
                            if (currentLogs.size > lastLogCount) {
                                val newLogs = currentLogs.drop(lastLogCount)
                                commandLogs.getOrPut(command.description()) { mutableListOf() }.addAll(newLogs)
                                lastLogCount = currentLogs.size
                            }
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

                val flowSuccess = orchestra.runFlow(commands, flowFile.parentFile.absolutePath)
                flowStatus = if (flowSuccess) FlowStatus.SUCCESS else FlowStatus.ERROR
            } catch (e: Exception) {
                logger.error("${shardPrefix}Failed to complete flow", e)
                flowStatus = FlowStatus.ERROR
                errorMessage = ErrorViewUtils.exceptionToMessage(e)
            }
        }
        val flowDuration = TimeUtils.durationInSeconds(flowTimeMillis)

        // Stop log capture and collect logs
        val capturedLogs = logCapture?.let {
            try {
                val logs = it.stop()
                logger.info("${shardPrefix}Captured ${logs.size} log entries")

                // Correlate logs with commands - split proportionally by duration
                if (logs.isNotEmpty()) {
                    val sortedCommands = debugOutput.commands.entries
                        .sortedBy { it.value.timestamp }
                        .toList()

                    val totalDuration = sortedCommands.sumOf { it.value.duration ?: 0L }

                    if (totalDuration > 0) {
                        var logIndex = 0
                        sortedCommands.forEachIndexed { index, (command, metadata) ->
                            val commandDuration = metadata.duration ?: 100L  // Give commands without duration some weight
                            // Allocate logs proportionally to command duration (min 1% per command)
                            val proportion = (commandDuration.toDouble() / totalDuration).coerceAtLeast(0.01)
                            val logsForThisCommand = (proportion * logs.size).toInt().coerceAtLeast(1)

                            val commandLogsList = logs.drop(logIndex).take(logsForThisCommand)
                            // Use index and add duration info to make keys unique and informative
                            val durationStr = if (commandDuration > 0) "${commandDuration}ms" else "instant"
                            val uniqueKey = "${index + 1}. ${command.description()}|||$durationStr"
                            commandLogs[uniqueKey] = commandLogsList.toMutableList()

                            logIndex += logsForThisCommand
                        }

                        // Add any remaining logs to the last command
                        if (logIndex < logs.size && sortedCommands.isNotEmpty()) {
                            val lastCommand = sortedCommands.last().key.description()
                            commandLogs.getOrPut(lastCommand) { mutableListOf() }
                                .addAll(logs.drop(logIndex))
                        }

                        logger.info("${shardPrefix}Split ${logs.size} logs across ${commandLogs.size} commands")
                    } else {
                        // If no durations, just put all logs under first command
                        if (sortedCommands.isNotEmpty()) {
                            commandLogs[sortedCommands.first().key.description()] = logs.toMutableList()
                        }
                    }
                }

                logs
            } catch (e: Exception) {
                logger.warn("${shardPrefix}Failed to stop log capture: ${e.message}")
                emptyList()
            }
        } ?: emptyList()

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
                logs = capturedLogs,
                commandLogs = commandLogs,
            ),
            second = aiOutput,
        )
    }

    private fun parseLogTimestamp(timestamp: String): Long? {
        // Parse "MM-DD HH:MM:SS.mmm" format to milliseconds
        try {
            val parts = timestamp.split(" ")
            if (parts.size < 2) return null

            val timePart = parts[1]  // "HH:MM:SS.mmm"
            val timeComponents = timePart.split(":")
            if (timeComponents.size < 3) return null

            val hour = timeComponents[0].toIntOrNull() ?: return null
            val minute = timeComponents[1].toIntOrNull() ?: return null
            val secondAndMillis = timeComponents[2].split(".")
            val second = secondAndMillis[0].toIntOrNull() ?: return null
            val millis = secondAndMillis.getOrNull(1)?.toIntOrNull() ?: 0

            val calendar = java.util.Calendar.getInstance()
            calendar.set(java.util.Calendar.HOUR_OF_DAY, hour)
            calendar.set(java.util.Calendar.MINUTE, minute)
            calendar.set(java.util.Calendar.SECOND, second)
            calendar.set(java.util.Calendar.MILLISECOND, millis)

            return calendar.timeInMillis
        } catch (e: Exception) {
            logger.debug("Failed to parse log timestamp: $timestamp", e)
            return null
        }
    }

}
