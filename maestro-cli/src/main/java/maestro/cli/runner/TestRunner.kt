package maestro.cli.runner

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.onFailure
import kotlinx.coroutines.runBlocking
import maestro.MaestroException
import maestro.device.Device
import maestro.device.Platform
import maestro.cli.report.FlowAIOutput
import maestro.cli.report.TestDebugReporter
import maestro.cli.runner.resultview.AnsiResultView
import maestro.cli.runner.resultview.ResultView
import maestro.cli.runner.resultview.UiState
import maestro.cli.util.PrintUtils
import maestro.cli.view.ErrorViewUtils
import maestro.orchestra.MaestroCommand
import maestro.orchestra.debug.FlowDebugOutput
import maestro.orchestra.devicecore.DeviceGateway
import maestro.orchestra.devicecore.RealDeviceGateway
import maestro.orchestra.util.Env.withEnv
import maestro.orchestra.util.Env.withDefaultEnvVars
import maestro.orchestra.util.Env.withInjectedShellEnvVars
import maestro.orchestra.yaml.YamlCommandReader
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Path
import kotlin.concurrent.thread

/**
 * Knows how to run a single Maestro flow (either one-shot or continuously).
 */
object TestRunner {

    private val logger = LoggerFactory.getLogger(TestRunner::class.java)

    /**
     * Runs a single flow, one-shot style.
     *
     * If the flow generates artifacts, they should be placed in [debugOutputPath].
     */
    fun runSingle(
        device: Device?,
        flowFile: File,
        env: Map<String, String>,
        resultView: ResultView,
        debugOutputPath: Path,
        analyze: Boolean = false,
        apiKey: String? = null,
        deviceId: String?,
        // The session-provisioned, connected device-core driver (W1.6). Defaults to an inert
        // instance only for callers that do no device op.
        driver: DeviceGateway = RealDeviceGateway(),
        platform: Platform? = null,
    ): Int {
        val debugOutput = FlowDebugOutput()
        var aiOutput = FlowAIOutput(
            flowName = flowFile.nameWithoutExtension,
            flowFile = flowFile,
        )

        val updatedEnv = env
            .withInjectedShellEnvVars()
            .withDefaultEnvVars(flowFile, deviceId)

        val commands = YamlCommandReader.readCommands(flowFile.toPath()).withEnv(updatedEnv)
        val flowName = YamlCommandReader.getConfig(commands)?.name ?: flowFile.nameWithoutExtension
        aiOutput = aiOutput.copy(flowName = flowName)
        logger.info("Running flow ${flowFile.name}...")

        // Per-flow folder ArtifactsGenerator writes the bundle into (see BundleLayout).
        val flowDir = TestDebugReporter.createFlowDir(debugOutputPath, flowName)

        val result = runCatching(resultView) {
            runBlocking {
                MaestroCommandRunner.runCommands(
                    flowName = flowName,
                    device = device,
                    view = resultView,
                    commands = commands,
                    debugOutput = debugOutput,
                    aiOutput = aiOutput,
                    analyze = analyze,
                    apiKey = apiKey,
                    artifactsDir = flowDir,
                    driver = driver,
                    platform = platform,
                )
            }
        }

        TestDebugReporter.saveSuggestions(outputs = listOf(aiOutput), path = debugOutputPath)

        debugOutput.exception?.let { printFlowError(it) }

        return if (result.get()?.success == true) 0 else 1
    }

    private fun printFlowError(exception: MaestroException) {
        PrintUtils.err(exception.message)
        val debugMessage = when (exception) {
            is MaestroException.AssertionFailure -> exception.debugMessage
            is MaestroException.HideKeyboardFailure -> exception.debugMessage
            is MaestroException.DriverTimeout -> exception.debugMessage
            else -> null
        }
        if (debugMessage != null) PrintUtils.err(debugMessage)
    }

    /**
     * Runs a single flow continuously.
     */
    fun runContinuous(
        device: Device?,
        flowFile: File,
        env: Map<String, String>,
        analyze: Boolean = false,
        apiKey: String? = null,
        deviceId: String?,
        // The session-provisioned, connected device-core driver (W1.6).
        driver: DeviceGateway = RealDeviceGateway(),
        platform: Platform? = null,
    ): Nothing {
        val resultView = AnsiResultView("> Press [ENTER] to restart the Flow\n\n")

        val fileWatcher = FileWatcher()

        var previousCommands: List<MaestroCommand>? = null

        var ongoingTest: Thread? = null
        do {
            val watchFiles = runCatching(resultView) {
                ongoingTest?.apply {
                    interrupt()
                    join()
                }

                val updatedEnv = env
                    .withInjectedShellEnvVars()
                    .withDefaultEnvVars(flowFile, deviceId)

                val commands = YamlCommandReader
                    .readCommands(flowFile.toPath())
                    .withEnv(updatedEnv)

                val flowName = YamlCommandReader.getConfig(commands)?.name

                // Restart the flow if anything has changed
                if (commands != previousCommands) {
                    ongoingTest = thread {
                        previousCommands = commands

                        runCatching(resultView) {
                            runBlocking {
                                MaestroCommandRunner.runCommands(
                                    flowName = flowName ?: flowFile.nameWithoutExtension,
                                    device = device,
                                    view = resultView,
                                    commands = commands,
                                    debugOutput = FlowDebugOutput(),
                                    // TODO(bartekpacia): make AI outputs work in continuous mode (see #1972)
                                    aiOutput = FlowAIOutput(
                                        flowName = "TODO",
                                        flowFile = flowFile,
                                    ),
                                    analyze = analyze,
                                    apiKey = apiKey,
                                    driver = driver,
                                    platform = platform,
                                )
                            }
                        }.get()
                    }
                }

                YamlCommandReader.getWatchFiles(flowFile.toPath())
            }
                .onFailure {
                    previousCommands = null
                }
                .getOr(listOf(flowFile.toPath()))

            if (CliWatcher.waitForFileChangeOrEnter(fileWatcher, watchFiles) == CliWatcher.SignalType.ENTER) {
                // On ENTER force re-run of flow even if commands have not changed
                previousCommands = null
            }
        } while (true)
    }

    private fun <T> runCatching(
        view: ResultView,
        block: () -> T,
    ): Result<T, Exception> {
        return try {
            Ok(block())
        } catch (e: Exception) {
            logger.error("Failed to run flow", e)
            val message = ErrorViewUtils.exceptionToMessage(e)

            // W1.6: the legacy `maestro.isShutDown()` guard (which suppressed the error view when the
            // device connection had already dropped) is gone with the Maestro facade. The device-core
            // seam exposes no shutdown probe yet, so we always surface the error — a failed run now
            // shows its error even if the underlying cause was a disconnect.
            view.setState(
                UiState.Error(
                    message = message
                )
            )
            return Err(e)
        }
    }
}
