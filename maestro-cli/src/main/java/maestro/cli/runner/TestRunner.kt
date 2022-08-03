package maestro.cli.runner

import maestro.Maestro
import maestro.orchestra.InvalidInitFlowFile
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroInitFlow
import maestro.orchestra.NoInputException
import maestro.orchestra.SyntaxError
import maestro.orchestra.yaml.YamlCommandReader
import java.io.File
import kotlin.concurrent.thread

object TestRunner {

    fun runSingle(
        maestro: Maestro,
        flowFile: File,
    ): Int {
        val view = ResultView()
        val result = runCatching(view) {
            val commands = YamlCommandReader.readCommands(flowFile)
            val initFlow = getInitFlow(commands)
            MaestroCommandRunner.runCommands(maestro, view, initFlow, commands, skipInitFlow = false)
        }
        return if (result?.flowSuccess == true) 0 else 1
    }

    fun runContinuous(
        maestro: Maestro,
        flowFile: File,
    ): Nothing {
        val view = ResultView()

        val fileWatcher = FileWatcher()

        var previousCommands: List<MaestroCommand>? = null
        var previousInitFlow: MaestroInitFlow? = null
        var previousResult: MaestroCommandRunner.Result? = null

        var ongoingTest: Thread? = null
        do {
            if (ongoingTest != null) {
                ongoingTest.interrupt()
                ongoingTest.join()
            }

            runCatching(view) {
                val commands = YamlCommandReader.readCommands(flowFile)
                val initFlow = getInitFlow(commands)

                ongoingTest = thread {
                    if (previousCommands == commands && initFlow == previousInitFlow) return@thread

                    previousResult = MaestroCommandRunner.runCommands(
                        maestro,
                        view,
                        initFlow,
                        commands,
                        // Skip init flow if previous init flow was successful and there were no changes to the init flow
                        skipInitFlow = previousResult?.initFlowSuccess == true && initFlow == previousInitFlow,
                    )

                    previousCommands = commands
                    previousInitFlow = initFlow
                }
            }

            val watchFiles = runCatching(view) {
                YamlCommandReader.getWatchFiles(flowFile)
            } ?: listOf(flowFile)

            fileWatcher.waitForChange(watchFiles)
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
                is InvalidInitFlowFile -> "initFlow file is invalid: ${e.initFlowFile}"
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