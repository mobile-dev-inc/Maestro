package maestro.cli.runner

import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import com.github.michaelbull.result.get
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.onFailure
import com.github.michaelbull.result.onSuccess
import maestro.Maestro
import maestro.orchestra.InvalidInitFlowFile
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroInitFlow
import maestro.orchestra.NoInputException
import maestro.orchestra.OrchestraAppState
import maestro.orchestra.SyntaxError
import maestro.orchestra.yaml.YamlCommandReader
import java.io.File
import kotlin.concurrent.thread

object TestRunner {

    fun runSingle(
        maestro: Maestro,
        flowFile: File,
        env: Map<String, String>,
    ): Int {
        val view = ResultView()
        val result = runCatching(view) {
            val commands = YamlCommandReader.readCommands(flowFile.toPath())
                .map { it.injectEnv(env) }
            MaestroCommandRunner.runCommands(
                maestro,
                view,
                commands,
                cachedAppState = null
            )
        }
        return if (result.get()?.flowSuccess == true) 0 else 1
    }

    fun runContinuous(
        maestro: Maestro,
        flowFile: File,
        env: Map<String, String>,
    ): Nothing {
        val view = ResultView("> Press [ENTER] to restart the Flow\n\n")

        val fileWatcher = FileWatcher()

        var previousCommands: List<MaestroCommand>? = null
        var previousInitFlow: MaestroInitFlow? = null
        var previousResult: MaestroCommandRunner.Result? = null

        var ongoingTest: Thread? = null
        do {
            val watchFiles = runCatching(view) {
                ongoingTest?.apply {
                    interrupt()
                    join()
                }

                val commands = YamlCommandReader.readCommands(flowFile.toPath())
                    .map { it.injectEnv(env) }
                val initFlow = getInitFlow(commands)

                // Restart the flow if anything has changed
                if (commands != previousCommands || initFlow != previousInitFlow) {
                    ongoingTest = thread {
                        // If previous init flow was successful and there were no changes to the init flow,
                        // then reuse cached app state (and skip the init commands)
                        val cachedAppState: OrchestraAppState? = if (initFlow == previousInitFlow) {
                            previousResult?.cachedAppState
                        } else {
                            null
                        }

                        previousResult = runCatching(view) {
                            MaestroCommandRunner.runCommands(
                                maestro,
                                view,
                                commands,
                                cachedAppState = cachedAppState,
                            )
                        }
                            .onSuccess {
                                previousCommands = commands
                                previousInitFlow = initFlow
                            }
                            .get()
                    }
                }

                YamlCommandReader.getWatchFiles(flowFile.toPath())
            }
                .onFailure {
                    previousCommands = null
                }
                .getOr(listOf(flowFile))

            if (CliWatcher.waitForFileChangeOrEnter(fileWatcher, watchFiles) == CliWatcher.SignalType.ENTER) {
                // On ENTER force re-run of flow even if commands have not changed
                previousCommands = null
            }
        } while (true)
    }

    private fun getInitFlow(commands: List<MaestroCommand>): MaestroInitFlow? {
        return YamlCommandReader.getConfig(commands)?.initFlow
    }

    private fun <T> runCatching(
        view: ResultView,
        block: () -> T,
    ): Result<T, Exception> {
        return try {
            Ok(block())
        } catch (e: Exception) {
            val message = when (e) {
                is SyntaxError -> "Could not parse Flow file:\n\n${e.message}"
                is NoInputException -> "No commands found in Flow file"
                is InvalidInitFlowFile -> "initFlow file is invalid: ${e.initFlowPath}"
                else -> e.stackTraceToString()
            }

            view.setState(
                ResultView.UiState.Error(
                    message = message
                )
            )
            return Err(e)
        }
    }
}