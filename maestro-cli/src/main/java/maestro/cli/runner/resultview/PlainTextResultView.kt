package maestro.cli.runner.resultview

import maestro.cli.runner.CommandState
import maestro.cli.runner.CommandStatus
import maestro.utils.Insight
import maestro.utils.chunkStringByWordCount

class PlainTextResultView: ResultView {

    private val printed = mutableSetOf<String>()

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

    private fun renderCommandPlainText(command: CommandState, indent: Int, commandKey: String) {
        val c = command.command.asCommand()
        if (c?.visible() == false) { return }

        val description = c?.description() ?: "Unknown command"
        val startKey = "$commandKey:$description:start"
        val completeKey = "$commandKey:$description:complete"

        if (command.subCommands != null) {
            // Command with subCommands
            if (command.status != CommandStatus.PENDING) {
                printOnce(startKey) { println("  ".repeat(indent) + "$description...") }
            }

            if (command.subOnStartCommands != null) {
                printOnce("$commandKey:onStart") { println("  ".repeat(indent + 1) + "> On Flow Start") }
                renderCommandsPlainText(command.subOnStartCommands, indent = indent + 1, prefix = "$commandKey:subOnStart")
            }

            renderCommandsPlainText(command.subCommands, indent = indent + 1, prefix = "$commandKey:sub")

            if (command.subOnCompleteCommands != null) {
                printOnce("$commandKey:onComplete") { println("  ".repeat(indent + 1) + "> On Flow Complete") }
                renderCommandsPlainText(command.subOnCompleteCommands, indent = indent + 1, prefix = "$commandKey:subOnComplete")
            }

            if (command.status in setOf(CommandStatus.COMPLETED, CommandStatus.FAILED, CommandStatus.SKIPPED, CommandStatus.WARNED)) {
                printOnce(completeKey) { println("  ".repeat(indent) + "$description... " + status(command.status)) }
            }
        } else {
            // Simple command without subCommands
            when (command.status) {
                CommandStatus.PENDING -> {
                    // Don't print pending commands
                }

                CommandStatus.RUNNING -> {
                    printOnce(startKey) { print("  ".repeat(indent) + "$description...") }
                }

                CommandStatus.COMPLETED, CommandStatus.FAILED, CommandStatus.SKIPPED, CommandStatus.WARNED -> {
                    printOnce(startKey) { print("  ".repeat(indent) + "$description...") }
                    printOnce(completeKey) {
                        println(" " + status(command.status))
                        renderInsight(command.insight, indent + 1)
                    }
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
