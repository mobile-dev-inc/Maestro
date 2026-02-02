package maestro.cli.runner.resultview

import maestro.cli.runner.CommandState
import maestro.cli.runner.CommandStatus
import maestro.utils.Insight
import maestro.utils.chunkStringByWordCount

class PlainTextResultView: ResultView {

    private val printedStartItems = mutableSetOf<String>()
    private val printedCompleteItems = mutableSetOf<String>()
    private var devicePrinted = false
    private var flowNamePrinted: String? = null
    private var onFlowStartPrinted = false
    private var onFlowCompletePrinted = false

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
        if (!devicePrinted) {
            state.device?.let {
                println("Running on ${state.device.description}")
                devicePrinted = true
            }
        }

        if (state.onFlowStartCommands.isNotEmpty()) {
            if (!onFlowStartPrinted) {
                println("  > On Flow Start")
                onFlowStartPrinted = true
            }
            renderCommandsPlainText(state.onFlowStartCommands, prefix = "onFlowStart")
        }

        if (flowNamePrinted != state.flowName) {
            println(" > Flow ${state.flowName}")
            flowNamePrinted = state.flowName
        }

        renderCommandsPlainText(state.commands, prefix = "main")

        if (state.onFlowCompleteCommands.isNotEmpty()) {
            if (!onFlowCompletePrinted) {
                println("  > On Flow Complete")
                onFlowCompletePrinted = true
            }
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
            if (startKey !in printedStartItems && command.status != CommandStatus.PENDING) {
                println("  ".repeat(indent) + "$description...")
                printedStartItems.add(startKey)
            }

            if (command.subOnStartCommands != null) {
                val onStartKey = "$commandKey:onStart"
                if (onStartKey !in printedStartItems) {
                    println("  ".repeat(indent + 1) + "> On Flow Start")
                    printedStartItems.add(onStartKey)
                }
                renderCommandsPlainText(command.subOnStartCommands, indent = indent + 1, prefix = "$commandKey:subOnStart")
            }

            renderCommandsPlainText(command.subCommands, indent = indent + 1, prefix = "$commandKey:sub")

            if (command.subOnCompleteCommands != null) {
                val onCompleteKey = "$commandKey:onComplete"
                if (onCompleteKey !in printedStartItems) {
                    println("  ".repeat(indent + 1) + "> On Flow Complete")
                    printedStartItems.add(onCompleteKey)
                }
                renderCommandsPlainText(command.subOnCompleteCommands, indent = indent + 1, prefix = "$commandKey:subOnComplete")
            }

            if (completeKey !in printedCompleteItems && command.status in setOf(CommandStatus.COMPLETED, CommandStatus.FAILED, CommandStatus.SKIPPED, CommandStatus.WARNED)) {
                println("  ".repeat(indent) + "$description... " + status(command.status))
                printedCompleteItems.add(completeKey)
            }
        } else {
            // Simple command without subCommands
            when (command.status) {
                CommandStatus.PENDING -> {
                    // Don't print pending commands
                }

                CommandStatus.RUNNING -> {
                    if (startKey !in printedStartItems) {
                        print("  ".repeat(indent) + "$description...")
                        printedStartItems.add(startKey)
                    }
                }

                CommandStatus.COMPLETED, CommandStatus.FAILED, CommandStatus.SKIPPED, CommandStatus.WARNED -> {
                    if (startKey !in printedStartItems) {
                        print("  ".repeat(indent) + "$description...")
                        printedStartItems.add(startKey)
                    }
                    if (completeKey !in printedCompleteItems) {
                        println(" " + status(command.status))
                        renderInsight(command.insight, indent + 1)
                        printedCompleteItems.add(completeKey)
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
