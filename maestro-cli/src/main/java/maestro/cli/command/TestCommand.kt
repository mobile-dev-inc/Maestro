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

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.cli.App
import maestro.cli.CliError
import maestro.cli.DisableAnsiMixin
import maestro.cli.ShowHelpMixin
import maestro.device.Device
import maestro.device.DeviceService
import maestro.cli.model.TestExecutionSummary
import maestro.cli.report.ReportFormat
import maestro.cli.report.ReporterFactory
import maestro.cli.report.TestDebugReporter
import maestro.cli.runner.TestRunner
import maestro.cli.runner.TestSuiteInteractor
import maestro.cli.runner.resultview.AnsiResultView
import maestro.cli.runner.resultview.PlainTextResultView
import maestro.cli.session.MaestroSessionManager
import maestro.cli.util.EnvUtils
import maestro.cli.util.FileUtils.isWebFlow
import maestro.cli.util.PrintUtils
import maestro.cli.insights.TestAnalysisManager
import maestro.cli.view.box
import maestro.orchestra.error.ValidationError
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import maestro.orchestra.workspace.WorkspaceExecutionPlanner.ExecutionPlan
import maestro.utils.isSingleFile
import okio.sink
import org.slf4j.LoggerFactory
import picocli.CommandLine
import picocli.CommandLine.Option
import java.io.File
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.math.roundToInt

@CommandLine.Command(
    name = "test",
    description = ["Test a Flow or set of Flows on a local iOS Simulator or Android Emulator"],
)
class TestCommand : Callable<Int> {

    @CommandLine.Mixin
    var disableANSIMixin: DisableAnsiMixin? = null

    @CommandLine.Mixin
    var showHelpMixin: ShowHelpMixin? = null

    @CommandLine.ParentCommand
    private val parent: App? = null

    @CommandLine.Parameters(description = ["One or more flow files or folders containing flow files"], arity = "1..*")
    private var flowFiles: Set<File> = emptySet()

    @Option(
        names = ["--config"],
        description = ["Optional YAML configuration file for the workspace. If not provided, Maestro will look for a config.yaml file in the workspace's root directory."]
    )
    private var configFile: File? = null

    @Option(
        names = ["-s", "--shards"],
        description = ["Number of parallel shards to distribute tests across"],
    )
    @Deprecated("Use --shard-split or --shard-all instead")
    private var legacyShardCount: Int? = null

    @Option(
        names = ["--shard-split"],
        description = ["Run the tests across N connected devices, splitting the tests evenly across them"],
    )
    private var shardSplit: Int? = null

    @Option(
        names = ["--shard-all"],
        description = ["Run all the tests across N connected devices"],
    )
    private var shardAll: Int? = null

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
        description = ["Configures the debug output in this path, instead of default"],
    )
    private var debugOutput: String? = null

    @Option(
        names = ["--flatten-debug-output"],
        description = ["All file outputs from the test case are created in the folder without subfolders or timestamps for each run. It can be used with --debug-output. Useful for CI."]
    )
    private var flattenDebugOutput: Boolean = false

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

    @Option(
        names = ["--headless"],
        description = ["(Web only) Run the tests in headless mode"],
    )
    private var headless: Boolean = false

    @Option(
        names = ["--analyze"],
        description = ["[Beta] Enhance the test output analysis with AI Insights"],
    )
    private var analyze: Boolean = false

    @Option(names = ["--api-url"], description = ["[Beta] API base URL"])
    private var apiUrl: String = "https://api.copilot.mobile.dev"


    @Option(names = ["--api-key"], description = ["[Beta] API key"])
    private var apiKey: String? = null

    @CommandLine.Spec
    lateinit var commandSpec: CommandLine.Model.CommandSpec

    private val usedPorts = ConcurrentHashMap<Int, Boolean>()
    private val logger = LoggerFactory.getLogger(TestCommand::class.java)

    private fun isWebFlow(): Boolean {
        if (flowFiles.isSingleFile) {
            return flowFiles.first().isWebFlow()
        }

        return false
    }


    override fun call(): Int {
        TestDebugReporter.install(
            debugOutputPathAsString = debugOutput,
            flattenDebugOutput = flattenDebugOutput,
            printToConsole = parent?.verbose == true,
        )

        if (shardSplit != null && shardAll != null) {
            throw CliError("Options --shard-split and --shard-all are mutually exclusive.")
        }

        @Suppress("DEPRECATION")
        if (legacyShardCount != null) {
            PrintUtils.warn("--shards option is deprecated and will be removed in the next Maestro version. Use --shard-split or --shard-all instead.")
            shardSplit = legacyShardCount
        }

        if (configFile != null && configFile?.exists()?.not() == true) {
            throw CliError("The config file ${configFile?.absolutePath} does not exist.")
        }

        val executionPlan = try {
            WorkspaceExecutionPlanner.plan(
                input = flowFiles.map { it.toPath().toAbsolutePath() }.toSet(),
                includeTags = includeTags,
                excludeTags = excludeTags,
                config = configFile?.toPath()?.toAbsolutePath(),
            )
        } catch (e: ValidationError) {
            throw CliError(e.message)
        }

        val debugOutputPath = TestDebugReporter.getDebugOutputPath()

        return handleSessions(debugOutputPath, executionPlan)
    }

    private fun handleSessions(debugOutputPath: Path, plan: ExecutionPlan): Int = runBlocking(Dispatchers.IO) {
        val requestedShards = shardSplit ?: shardAll ?: 1
        if (requestedShards > 1 && plan.sequence.flows.isNotEmpty()) {
            error("Cannot run sharded tests with sequential execution")
        }

        val onlySequenceFlows = plan.sequence.flows.isNotEmpty() && plan.flowsToRun.isEmpty() // An edge case

        val availableDevices = DeviceService.listConnectedDevices(
            includeWeb = isWebFlow(),
            host = parent?.host,
            port = parent?.port,
        ).map { it.instanceId }.toSet()
        val passedDeviceIds = getPassedOptionsDeviceIds()
            .filter { device ->
                if (device !in availableDevices) {
                    throw CliError("Device $device was requested, but it is not connected.")
                } else {
                    true
                }
            }
            // Don't default to all available devices here. Handle it based on sharding later.
            // .ifEmpty { availableDevices }
            // .toList()

        // Determine effective shards based on requested shards and flow count/type
        val effectiveShards = when {
            onlySequenceFlows -> 1 // Sequence flows always run on one device
            shardAll != null -> requestedShards.coerceAtMost(DeviceService.listConnectedDevices().size) // Cannot run more shards than connected devices if replicating all flows
            shardSplit != null -> requestedShards.coerceAtMost(plan.flowsToRun.size).coerceAtMost(DeviceService.listConnectedDevices().size) // Cannot run more shards than flows or connected devices
            else -> 1 // Default to 1 shard if no sharding options are provided
        }

        // Determine the list of device IDs to use for the shards
        val deviceIds: List<String?> = if (passedDeviceIds.isNotEmpty()) {
            // If specific devices were passed, use them
            passedDeviceIds
        } else {
            // If no devices were passed...
            if (effectiveShards == 1) {
                // For a single shard, pass null to trigger device selection prompt if needed
                listOf(null)
            } else {
                // For multiple shards, use all available connected devices.
                // Note: This maintains existing behavior for sharding without explicit devices.
                // The user might want prompting even for sharding, but that's a larger change.
                val connectedDeviceIds = availableDevices.toList()
                if (connectedDeviceIds.size < effectiveShards) {
                    throw CliError("Not enough devices connected (${connectedDeviceIds.size}) to run the requested number of shards ($effectiveShards).")
                }
                // Use only as many devices as needed for the shards
                connectedDeviceIds.take(effectiveShards)
            }
        }


        // Validate if enough devices are available for the requested shards IF specific devices were requested OR sharding is used without specific devices
        if (passedDeviceIds.isNotEmpty() || (passedDeviceIds.isEmpty() && effectiveShards > 1)) {
            val missingDevices = effectiveShards - deviceIds.size
            if (missingDevices > 0) {
                 PrintUtils.warn("Want to use ${deviceIds.size} devices, which is not enough to run $effectiveShards shards. Missing $missingDevices device(s).")
                 throw CliError("Not enough devices available ($missingDevices missing) for the requested number of shards ($effectiveShards). Ensure devices are connected or reduce shard count.")
            }
        }

        // Existing shard warning logic (adjusting message slightly)
        if (shardAll == null && shardSplit != null && requestedShards > plan.flowsToRun.size) {
            val warning = "Requested $requestedShards shards, " +
                    "but cannot run more shards than flows (${plan.flowsToRun.size}). " +
                    "Will use $effectiveShards shards instead."
            PrintUtils.warn(warning)
        } else if (shardAll != null && requestedShards > availableDevices.size) {
             val warning = "Requested $requestedShards shards, " +
                    "but cannot run more shards than connected devices (${availableDevices.size}). " +
                    "Will use $effectiveShards shards instead."
             PrintUtils.warn(warning)
        }


        val chunkPlans = makeChunkPlans(plan, effectiveShards, onlySequenceFlows)

        val flowCount = if (onlySequenceFlows) plan.sequence.flows.size else plan.flowsToRun.size
        val message = when {
            shardAll != null -> "Will run $effectiveShards shards, with all $flowCount flows in each shard"
            shardSplit != null -> {
                val flowsPerShard = (flowCount.toFloat() / effectiveShards).roundToInt()
                val isApprox = flowCount % effectiveShards != 0
                val prefix = if (isApprox) "approx. " else ""
                "Will split $flowCount flows across $effectiveShards shards (${prefix}$flowsPerShard flows per shard)"
            }

            else -> null
        }
        message?.let { PrintUtils.info(it) }

        val results = (0 until effectiveShards).map { shardIndex ->
            async(Dispatchers.IO + CoroutineName("shard-$shardIndex")) {
                runShardSuite(
                    effectiveShards = effectiveShards,
                    deviceIds = deviceIds,
                    shardIndex = shardIndex,
                    chunkPlans = chunkPlans,
                    debugOutputPath = debugOutputPath,
                )
            }
        }.awaitAll()

        val passed = results.sumOf { it.first ?: 0 }
        val total = results.sumOf { it.second ?: 0 }
        val suites = results.mapNotNull { it.third }

        suites.mergeSummaries()?.saveReport()

        if (effectiveShards > 1) printShardsMessage(passed, total, suites)
        if (analyze) TestAnalysisManager(apiUrl = apiUrl, apiKey = apiKey).runAnalysis(debugOutputPath)
        if (passed == total) 0 else 1
    }

    private suspend fun runShardSuite(
        effectiveShards: Int,
        deviceIds: List<String?>, // Allow null device IDs
        shardIndex: Int,
        chunkPlans: List<ExecutionPlan>,
        debugOutputPath: Path,
    ): Triple<Int?, Int?, TestExecutionSummary?> {
        val driverHostPort = selectPort(effectiveShards)
        // DeviceId can be null if we need to prompt
        val deviceId: String? = deviceIds[shardIndex]

        // Log device selection or indicate prompting might occur
        if (deviceId != null) {
            logger.info("[shard ${shardIndex + 1}] Selected device $deviceId using port $driverHostPort")
        } else {
            logger.info("[shard ${shardIndex + 1}] No device specified, will attempt connection or prompt for selection. Using port $driverHostPort")
        }


        return MaestroSessionManager.newSession(
            host = parent?.host,
            port = parent?.port,
            driverHostPort = driverHostPort,
            deviceId = deviceId, // Pass the potentially null deviceId
            platform = parent?.platform,
            isHeadless = headless,
        ) { session ->
            val maestro = session.maestro
            val device = session.device

            val isReplicatingSingleFile = shardAll != null && effectiveShards > 1 && flowFiles.isSingleFile
            val isMultipleFiles = flowFiles.isSingleFile.not()
            val isAskingForReport = format != ReportFormat.NOOP
            if (isMultipleFiles || isAskingForReport || isReplicatingSingleFile) {
                if (continuous) {
                    throw CommandLine.ParameterException(
                        commandSpec.commandLine(),
                        "Continuous mode is not supported when running multiple flows. (${flowFiles.joinToString(", ")})",
                    )
                }
                runMultipleFlows(maestro, device, chunkPlans, shardIndex, debugOutputPath)
            } else {
                val flowFile = flowFiles.first()
                if (continuous) {
                    if (!flattenDebugOutput) {
                        TestDebugReporter.deleteOldFiles()
                    }
                    TestRunner.runContinuous(maestro, device, flowFile, env, analyze)
                } else {
                    runSingleFlow(maestro, device, flowFile, debugOutputPath)
                }
            }
        }
    }

    private fun selectPort(effectiveShards: Int): Int =
        if (effectiveShards == 1) 7001
        else (7001..7128).shuffled().find { port ->
            usedPorts.putIfAbsent(port, true) == null
        } ?: error("No available ports found")

    private fun runSingleFlow(
        maestro: Maestro,
        device: Device?,
        flowFile: File,
        debugOutputPath: Path,
    ): Triple<Int, Int, Nothing?> {
        val resultView =
            if (DisableAnsiMixin.ansiEnabled) {
                AnsiResultView(useEmojis = !EnvUtils.isWindows())
            } else {
                PlainTextResultView()
            }

        val resultSingle = TestRunner.runSingle(
            maestro = maestro,
            device = device,
            flowFile = flowFile,
            env = env,
            resultView = resultView,
            debugOutputPath = debugOutputPath,
            analyze = analyze
        )

        if (resultSingle == 1) {
            printExitDebugMessage()
        }

        if (!flattenDebugOutput) {
            TestDebugReporter.deleteOldFiles()
        }

        val result = if (resultSingle == 0) 1 else 0
        return Triple(result, 1, null)
    }

    private fun runMultipleFlows(
        maestro: Maestro,
        device: Device?,
        chunkPlans: List<ExecutionPlan>,
        shardIndex: Int,
        debugOutputPath: Path
    ): Triple<Int?, Int?, TestExecutionSummary> {
        val suiteResult = TestSuiteInteractor(
            maestro = maestro,
            device = device,
            shardIndex = if (chunkPlans.size == 1) null else shardIndex,
            reporter = ReporterFactory.buildReporter(format, testSuiteName),
        ).runTestSuite(
            executionPlan = chunkPlans[shardIndex],
            env = env,
            reportOut = null,
            debugOutputPath = debugOutputPath
        )

        if (!flattenDebugOutput) {
            TestDebugReporter.deleteOldFiles()
        }
        return Triple(suiteResult.passedCount, suiteResult.totalTests, suiteResult)
    }

    private fun makeChunkPlans(
        plan: ExecutionPlan,
        effectiveShards: Int,
        onlySequenceFlows: Boolean,
    ) = when {
        onlySequenceFlows -> listOf(plan) // We only want to run sequential flows in this case.
        shardAll != null -> (0 until effectiveShards).reversed().map { plan.copy() }
        else -> plan.flowsToRun
            .withIndex()
            .groupBy { it.index % effectiveShards }
            .map { (_, files) ->
                val flowsToRun = files.map { it.value }
                ExecutionPlan(flowsToRun, plan.sequence)
            }
    }

    private fun getPassedOptionsDeviceIds(): List<String> {
        val arguments = if (isWebFlow()) {
            PrintUtils.warn("Web support is in Beta. We would appreciate your feedback!\n")
            // Don't automatically default to "chromium" - let the sharding logic handle device selection
            parent?.deviceId
        } else parent?.deviceId
        val deviceIds = arguments
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        return deviceIds
    }

    private fun printExitDebugMessage() {
        println()
        println("==== Debug output (logs & screenshots) ====")
        PrintUtils.message(TestDebugReporter.getDebugOutputPath().absolutePathString())
    }

    private fun printShardsMessage(passedTests: Int, totalTests: Int, shardResults: List<TestExecutionSummary>) {
        val lines = listOf("Passed: $passedTests/$totalTests") +
                shardResults.mapIndexed { _, result ->
                    "[ ${result.suites.first().deviceName} ] - ${result.passedCount ?: 0}/${result.totalTests ?: 0}"
                }
        PrintUtils.message(lines.joinToString("\n").box())
    }

    private fun TestExecutionSummary.saveReport() {
        val reporter = ReporterFactory.buildReporter(format, testSuiteName)

        format.fileExtension?.let { extension ->
            (output ?: File("report$extension")).sink()
        }?.also { sink ->
            reporter.report(this, sink)
        }
    }

    private fun List<TestExecutionSummary>.mergeSummaries(): TestExecutionSummary? = reduceOrNull { acc, summary ->
        TestExecutionSummary(
            passed = acc.passed && summary.passed,
            suites = acc.suites + summary.suites,
            passedCount = sumOf { it.passedCount ?: 0 },
            totalTests = sumOf { it.totalTests ?: 0 }
        )
    }
}
