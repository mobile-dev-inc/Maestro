package maestro.cli.runner.resultview

import io.ktor.util.encodeBase64
import maestro.cli.runner.CommandState
import maestro.orchestra.debug.CommandStatus
import maestro.orchestra.CompositeCommand
import maestro.utils.Insight
import maestro.utils.chunkStringByWordCount

class PlainTextResultView: ResultView {

    private val printed = mutableSetOf<String>()

    private val output = StringBuilder()

    private val frames = mutableListOf<Frame>()

    private val startTimestamp = System.currentTimeMillis()

    private val terminalStatuses = setOf(
        CommandStatus.COMPLETED,
        CommandStatus.FAILED,
        CommandStatus.SKIPPED,
        CommandStatus.WARNED
    )

    private inline fun printOnce(key: String, block: () -> Unit) {
        if (printed.add(key)) block()
    }

    override fun getFrames(): List<Frame> {
        return frames.toList()
    }

    private fun emit(text: String) {
        output.append(text)
        print(text)
    }

    private fun emitLine(text: String = "") {
        output.append(text).append('\n')
        println(text)
    }

    override fun setState(state: UiState) {
        when (state) {
            is UiState.Running -> renderRunningState(state)
            is UiState.Error -> renderErrorState(state)
        }

        frames.add(
            Frame(
                timestamp = System.currentTimeMillis() - startTimestamp,
                content = output.toString().encodeBase64(),
            )
        )
    }

    private fun renderErrorState(state: UiState.Error) {
        emitLine(state.message)
    }

    private fun renderRunningState(state: UiState.Running) {
        renderRunningStatePlainText(state)
    }

    private fun renderRunningStatePlainText(state: UiState.Running) {
        state.device?.let {
            printOnce("device") { emitLine("Running on ${it.description}") }
        }

        if (state.onFlowStartCommands.isNotEmpty()) {
            printOnce("onFlowStart") { emitLine("  > On Flow Start") }
            renderCommandsPlainText(state.onFlowStartCommands, prefix = "onFlowStart")
        }

        printOnce("flowName:${state.flowName}") { emitLine(" > Flow ${state.flowName}") }

        renderCommandsPlainText(state.commands, prefix = "main")

        if (state.onFlowCompleteCommands.isNotEmpty()) {
            printOnce("onFlowComplete") { emitLine("  > On Flow Complete") }
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
                    printOnce("$key:start") { emitLine("$pad$desc...") }
                }

                // onFlowStart hooks
                command.subOnStartCommands?.let { cmds ->
                    printOnce("$key:onStart") { emitLine("$pad  > On Flow Start") }
                    renderCommandsPlainText(cmds, indent + 1, "$key:subOnStart")
                }

                // The actual sub-commands of the composite
                command.subCommands?.let { cmds ->
                    renderCommandsPlainText(cmds, indent + 1, "$key:sub")
                }

                // onFlowComplete hooks
                command.subOnCompleteCommands?.let { cmds ->
                    printOnce("$key:onComplete") { emitLine("$pad  > On Flow Complete") }
                    renderCommandsPlainText(cmds, indent + 1, "$key:subOnComplete")
                }

                // Print completion line once when it reaches a terminal status
                if (command.status in terminalStatuses) {
                    printOnce("$key:complete") { emitLine("$pad$desc... ${status(command.status)}") }
                }
            }

            else -> {
                // Simple command (tapOn, assertVisible, etc.)
                when (command.status) {
                    CommandStatus.RUNNING -> {
                        printOnce("$key:start") { emit("$pad$desc...") }
                    }

                    in terminalStatuses -> {
                        printOnce("$key:start") { emit("$pad$desc...") }
                        printOnce("$key:complete") {
                            emitLine(" ${status(command.status)}")
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
            emitLine("\n")
            val level = insight.level.toString().lowercase().replaceFirstChar(Char::uppercase)
            emit(" ".repeat(indent) + level + ":")
            insight.message.chunkStringByWordCount(12).forEach { chunkedMessage ->
                emit(" ".repeat(indent))
                emit(chunkedMessage)
                emit("\n")
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
