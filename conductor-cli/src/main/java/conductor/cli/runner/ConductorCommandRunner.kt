package conductor.cli.runner

import conductor.Conductor
import conductor.orchestra.CommandReader
import conductor.orchestra.ConductorCommand
import conductor.orchestra.Orchestra
import okio.source
import java.io.File

class ConductorCommandRunner(
    private val conductor: Conductor,
    private val view: ResultView,
    private val commandReader: CommandReader,
) {

    fun run(testFile: File): Boolean {
        val commands = try {
            testFile.source().use {
                commandReader.readCommands(it)
            }
        } catch (e: Exception) {
            val message = when {
                e is CommandReader.SyntaxError -> "Syntax error"
                e is CommandReader.NoInputException -> "No commands in the file"
                e.message != null -> e.message!!
                else -> "Failed to read commands"
            }

            view.setState(
                ResultView.UiState.Error(
                    message = message
                )
            )
            return false
        }

        return runCommands(commands)
    }

    private fun runCommands(
        commands: List<ConductorCommand>,
    ): Boolean {
        val indexToStatus = Array(commands.size) { CommandStatus.PENDING }

        fun refreshUi() {
            view.setState(
                ResultView.UiState.Running(
                    commands = commands
                        .mapIndexed { idx, command ->
                            CommandState(
                                command = command,
                                status = indexToStatus[idx]
                            )
                        },
                )
            )
        }

        refreshUi()

        var success = true
        Orchestra(
            conductor,
            onCommandStart = { idx, _ ->
                indexToStatus[idx] = CommandStatus.RUNNING
                refreshUi()
            },
            onCommandComplete = { idx, _ ->
                indexToStatus[idx] = CommandStatus.COMPLETED
                refreshUi()
            },
            onCommandFailed = { idx, _, _ ->
                indexToStatus[idx] = CommandStatus.FAILED
                refreshUi()
                success = false
            },
        ).executeCommands(commands)

        return success
    }

}