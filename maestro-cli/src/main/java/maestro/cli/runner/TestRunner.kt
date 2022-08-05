package maestro.cli.runner

import maestro.Maestro
import maestro.orchestra.InvalidInitFlowFile
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroInitFlow
import maestro.orchestra.NoInputException
import maestro.orchestra.SyntaxError
import maestro.orchestra.yaml.YamlCommandReader
import java.io.File
import java.nio.file.Path
import kotlin.concurrent.thread

object TestRunner {

    fun runSingle(
        maestro: Maestro,
        flowFile: File,
    ): Int {
        val view = ResultView()
        val result = runCatching(view) {
            val commands = YamlCommandReader.readCommands(flowFile.toPath())
            val initFlow = getInitFlow(commands)
            MaestroCommandRunner.runCommands(maestro, view, initFlow, commands, cachedAppState = null)
        }
        return if (result?.flowSuccess == true) 0 else 1
    }

    fun runContinuous(
        maestro: Maestro,
        flowFile: File,
    ): Nothing {
        val view = ResultView("> Press [ENTER] to restart the Flow\n\n")

        val fileWatcher = FileWatcher()

        var previousCommands: List<MaestroCommand>? = null
        var previousInitFlow: MaestroInitFlow? = null
        var previousResult: MaestroCommandRunner.Result? = null

        var ongoingTest: Thread? = null
        do {
            val commands = YamlCommandReader.readCommands(flowFile.toPath())
            val initFlow = getInitFlow(commands)

            // Restart the flow if anything has changed
            if (commands != previousCommands || initFlow != previousInitFlow) {
                if (ongoingTest != null) {
                    ongoingTest.interrupt()
                    ongoingTest.join()
                }

                runCatching(view) {
                    ongoingTest = thread {
                        // If previous init flow was successful and there were no changes to the init flow,
                        // then reuse cached app state (and skip the init commands)
                        val cachedAppState: File? = if (initFlow == previousInitFlow) {
                            previousResult?.cachedAppState
                        } else {
                            null
                        }

                        previousCommands = commands
                        previousInitFlow = initFlow

                        previousResult = MaestroCommandRunner.runCommands(
                            maestro,
                            view,
                            initFlow,
                            commands,
                            cachedAppState = cachedAppState,
                        )
                    }
                }
            }

            val watchFiles = runCatching(view) {
                YamlCommandReader.getWatchFiles(flowFile.toPath())
            } ?: listOf(flowFile)

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
    ): T? {
        return try {
            block()
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
            return null
        }
    }
}