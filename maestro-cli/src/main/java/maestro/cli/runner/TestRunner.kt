package maestro.cli.runner

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.onFailure
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.MaestroException
import maestro.device.Device
import maestro.cli.report.FlowAIOutput
import maestro.cli.report.FlowDebugOutput
import maestro.cli.report.TestDebugReporter
import maestro.cli.runner.resultview.AnsiResultView
import maestro.cli.runner.resultview.ResultView
import maestro.cli.runner.resultview.UiState
import maestro.cli.util.EnvUtils
import maestro.cli.util.PrintUtils
import maestro.cli.view.ErrorViewUtils
import maestro.orchestra.MaestroCommand
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
        maestro: Maestro,
        device: Device?,
        flowFile: File,
        env: Map<String, String>,
        resultView: ResultView,
        debugOutputPath: Path,
        analyze: Boolean = false,
        apiKey: String? = null,
        testOutputDir: Path?,
        deviceId: String?,
    ): Int {
        val debugOutput = FlowDebugOutput()
        var aiOutput = FlowAIOutput(
            flowName = flowFile.nameWithoutExtension,
            flowFile = flowFile,
        )

        val updatedEnv = env
            .withInjectedShellEnvVars()
            .withDefaultEnvVars(flowFile, deviceId)

        val result = runCatching(resultView, maestro) {
            val commands = YamlCommandReader.readCommands(flowFile.toPath())
                .withEnv(updatedEnv)

            val flowName = YamlCommandReader.getConfig(commands)?.name ?: flowFile.nameWithoutExtension
            aiOutput = aiOutput.copy(flowName = flowName)

            logger.info("Running flow ${flowFile.name}...")

            runBlocking {
                MaestroCommandRunner.runCommands(
                    flowName = flowName,
                    maestro = maestro,
                    device = device,
                    view = resultView,
                    commands = commands,
                    debugOutput = debugOutput,
                    aiOutput = aiOutput,
                    analyze = analyze,
                    testOutputDir = testOutputDir,
                )
            }
        }

        TestDebugReporter.saveFlow(
            flowName = flowFile.name,
            debugOutput = debugOutput,
            path = debugOutputPath,
        )
        TestDebugReporter.saveSuggestions(
            outputs = listOf(aiOutput),
            path = debugOutputPath,
        )

        val exception = debugOutput.exception
        if (exception != null) {
            PrintUtils.err(exception.message)
            if (exception is MaestroException.AssertionFailure) {
                PrintUtils.err(exception.debugMessage)
            } else if (exception is MaestroException.HideKeyboardFailure) {
                PrintUtils.err(exception.debugMessage)
            } else {
                val debugMessage = (exception as? MaestroException.DriverTimeout)?.debugMessage
                if (exception is MaestroException.DriverTimeout && debugMessage != null) {
                    PrintUtils.err(debugMessage)
                }
            }
        }

        return if (result.get() == true) 0 else 1
    }

    /**
     * Runs a single flow continuously.
     */
    fun runContinuous(
        maestro: Maestro,
        device: Device?,
        flowFile: File,
        env: Map<String, String>,
        analyze: Boolean = false,
        apiKey: String? = null,
        testOutputDir: Path?,
        deviceId: String?,
    ): Nothing {
        val resultView = AnsiResultView("> Press [ENTER] to restart the Flow\n\n", useEmojis = !EnvUtils.isWindows())

        val fileWatcher = FileWatcher()

        var previousCommands: List<MaestroCommand>? = null

        var ongoingTest: Thread? = null
        do {
            val watchFiles = runCatching(resultView, maestro) {
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

                        runCatching(resultView, maestro) {
                            runBlocking {
                                MaestroCommandRunner.runCommands(
                                    flowName = flowName ?: flowFile.nameWithoutExtension,
                                    maestro = maestro,
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
                                    testOutputDir = testOutputDir
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
        maestro: Maestro,
        block: () -> T,
    ): Result<T, Exception> {
        return try {
            Ok(block())
        } catch (e: Exception) {
            logger.error("Failed to run flow", e)
            val message = ErrorViewUtils.exceptionToMessage(e)

            if (!maestro.isShutDown()) {
                view.setState(
                    UiState.Error(
                        message = message
                    )
                )
            }
            return Err(e)
        }
    }
}
