package maestro.cli.runner.resultview

import maestro.cli.runner.CommandState
import maestro.cli.runner.CommandStatus
import maestro.orchestra.CompositeCommand
import maestro.utils.Insight
import maestro.utils.chunkStringByWordCount

class PlainTextResultView: ResultView {

    private val printed = mutableSetOf<String>()

    private val terminalStatuses = setOf(
        CommandStatus.COMPLETED,
        CommandStatus.FAILED,
        CommandStatus.SKIPPED,
        CommandStatus.WARNED
    )

    private inline fun printOnce(key: String, block: () -> Unit) {
        if (printed.add(key)) block()
    }

    override fun setState(state: UiState) {
        when (state) {
            is UiState.Running -> renderRunningState(state)
            is UiState.Error -> renderErrorState(state)
        }
    }

    private fun renderErrorState(state: UiState.Error) {
        println(state.message)
    }

    private fun renderRunningState(state: UiState.Running) {
        renderRunningStatePlainText(state)
    }

    private fun renderRunningStatePlainText(state: UiState.Running) {
        state.device?.let {
            printOnce("device") { println("Running on ${state.device.description}") }
        }

        if (state.onFlowStartCommands.isNotEmpty()) {
            printOnce("onFlowStart") { println("  > On Flow Start") }
            renderCommandsPlainText(state.onFlowStartCommands, prefix = "onFlowStart")
        }

        printOnce("flowName:${state.flowName}") { println(" > Flow ${state.flowName}") }

        renderCommandsPlainText(state.commands, prefix = "main")

        if (state.onFlowCompleteCommands.isNotEmpty()) {
            printOnce("onFlowComplete") { println("  > On Flow Complete") }
            renderCommandsPlainText(state.onFlowCompleteCommands, prefix = "onFlowComplete")
        }
    }

    private fun renderCommandsPlainText(commands: List<CommandState>, indent: Int = 0, prefix: String = "") {
        for ((index, command) in commands.withIndex()) {
            renderCommandPlainText(command, indent, "$prefix:$index")
        }
    }

    private fun renderCommandPlainText(command: CommandState, indent: Int, key: String) {
        val c = command.command.asCommand()
        if (c?.visible() == false) return

        val desc = c?.description() ?: "Unknown command"
        val pad = "  ".repeat(indent)

        when (c) {
            is CompositeCommand -> {
                // Print start line once when command begins
                if (command.status != CommandStatus.PENDING) {
                    printOnce("$key:start") { println("$pad$desc...") }
                }

                // onFlowStart hooks
                command.subOnStartCommands?.let { cmds ->
                    printOnce("$key:onStart") { println("$pad  > On Flow Start") }
                    renderCommandsPlainText(cmds, indent + 1, "$key:subOnStart")
                }

                // The actual sub-commands of the composite
                command.subCommands?.let { cmds ->
                    renderCommandsPlainText(cmds, indent + 1, "$key:sub")
                }

                // onFlowComplete hooks
                command.subOnCompleteCommands?.let { cmds ->
                    printOnce("$key:onComplete") { println("$pad  > On Flow Complete") }
                    renderCommandsPlainText(cmds, indent + 1, "$key:subOnComplete")
                }

                // Print completion line once when it reaches a terminal status
                if (command.status in terminalStatuses) {
                    printOnce("$key:complete") { println("$pad$desc... ${status(command.status)}") }
                }
            }

            else -> {
                // Simple command (tapOn, assertVisible, etc.)
                when (command.status) {
                    CommandStatus.RUNNING -> {
                        printOnce("$key:start") { print("$pad$desc...") }
                    }

                    in terminalStatuses -> {
                        printOnce("$key:start") { print("$pad$desc...") }
                        printOnce("$key:complete") {
                            println(" ${status(command.status)}")
                            renderInsight(command.insight, indent + 1)
                        }
                    }

                    else -> {}
                }
            }
        }
    }

    private fun renderInsight(insight: Insight, indent: Int) {
        if (insight.level != Insight.Level.NONE) {
            println("\n")
            val level = insight.level.toString().lowercase().replaceFirstChar(Char::uppercase)
            print(" ".repeat(indent) + level + ":")
            insight.message.chunkStringByWordCount(12).forEach { chunkedMessage ->
                print(" ".repeat(indent))
                print(chunkedMessage)
                print("\n")
            }
        }
    }

    private fun status(status: CommandStatus): String {
        return when (status) {
            CommandStatus.COMPLETED -> "COMPLETED"
            CommandStatus.FAILED -> "FAILED"
            CommandStatus.RUNNING -> "RUNNING"
            CommandStatus.PENDING -> "PENDING"
            CommandStatus.SKIPPED -> "SKIPPED"
            CommandStatus.WARNED -> "WARNED"
        }
    }
}
