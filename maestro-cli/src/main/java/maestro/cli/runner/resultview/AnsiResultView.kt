/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro.cli.runner.resultview

import io.ktor.util.encodeBase64
import maestro.device.Device
import maestro.device.Platform
import maestro.cli.runner.CommandState
import maestro.cli.runner.CommandStatus
import maestro.orchestra.AssertWithAICommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.utils.Insight
import maestro.utils.chunkStringByWordCount
import org.jline.jansi.Ansi

class AnsiResultView(
    private val prompt: String? = null,
    private val printCommandLogs: Boolean = true,
    private val useEmojis: Boolean = true,
) : ResultView {

    private val startTimestamp = System.currentTimeMillis()

    private val frames = mutableListOf<Frame>()

    private var previousFrame: String? = null

    init {
        println(Ansi.ansi().eraseScreen())
    }

    override fun setState(state: UiState) {
        when (state) {
            is UiState.Running -> renderRunningState(state)
            is UiState.Error -> renderErrorState(state)
        }
    }

    fun getFrames(): List<Frame> {
        return frames.toList()
    }

    private fun renderErrorState(state: UiState.Error) {
        renderFrame {
            fgRed()
            render(state.message)
            render("\n")
        }
    }

    private fun renderRunningState(state: UiState.Running) = renderFrame {
        state.device?.let {
            render("Running on ${state.device.description}\n")
        }
        render("\n")
        if (state.onFlowStartCommands.isNotEmpty()) {
            render(" ║\n")
            render(" ║  > On Flow Start\n")
            render(" ║\n")
            renderCommands(state.onFlowStartCommands)
        }
        render(" ║\n")
        render(" ║  > Flow: ${state.flowName}\n")
        render(" ║\n")
        renderCommands(state.commands)
        render(" ║\n")
        if (state.onFlowCompleteCommands.isNotEmpty()) {
            render(" ║\n")
            render(" ║  > On Flow Complete\n")
            render(" ║\n")
            renderCommands(state.onFlowCompleteCommands)
        }
        renderPrompt()
    }

    private fun Ansi.renderPrompt() {
        prompt?.let {
            render(" ║\n")
            render(" ║  $prompt\n")
        }
    }

    private fun Ansi.renderCommands(
        commands: List<CommandState>,
        indent: Int = 0,
    ) {
        commands
            .filter { it.command.asCommand()?.visible() ?: true }
            .forEach { renderCommand(it, indent) }
    }

    private fun Ansi.renderCommand(commandState: CommandState, indent: Int) {
        val statusSymbol = status(commandState.status)

        fgDefault()
        renderLineStart(indent)
        render(statusSymbol)
        render(" ".repeat(2))
        render(
            commandState.command.description()
                .replace("(?<!\\\\)\\\$\\{.*}".toRegex()) { match ->
                    "@|cyan ${match.value}|@"
                }
        )

        if (commandState.status == CommandStatus.SKIPPED) {
            render(" (skipped)")
        } else if (commandState.status == CommandStatus.WARNED) {
            render(" (warned)")
        } else if (commandState.numberOfRuns != null) {
            val timesWord = if (commandState.numberOfRuns == 1) "time" else "times"
            render(" (completed ${commandState.numberOfRuns} $timesWord)")
        }

        render("\n")

        if (printCommandLogs && commandState.logMessages.isNotEmpty()) {
            printLogMessages(indent, commandState)
        }

        if (commandState.insight.level != Insight.Level.NONE) {
            printInsight(indent, commandState.insight)
        }


        val subCommandsHasNotPending = (commandState.subCommands
            ?.any { subCommand -> subCommand.status != CommandStatus.PENDING } ?: false)
        val onStartHasNotPending = (commandState.subOnStartCommands
            ?.any { subCommand -> subCommand.status != CommandStatus.PENDING } ?: false)
        val onCompleteHasNotPending = (commandState.subOnCompleteCommands
            ?.any { subCommand -> subCommand.status != CommandStatus.PENDING } ?: false)
        val expandSubCommands = commandState.status in setOf(CommandStatus.RUNNING, CommandStatus.FAILED) &&
                (subCommandsHasNotPending || onStartHasNotPending || onCompleteHasNotPending)

        if (expandSubCommands) {
            commandState.subOnStartCommands?.let {
                render(" ║\n")
                render(" ║  > On Flow Start\n")
                render(" ║\n")
                renderCommands(it)
            }

            commandState.subCommands?.let { subCommands ->
                renderCommands(subCommands, indent + 1)
            }

            commandState.subOnCompleteCommands?.let {
                render(" ║\n")
                render(" ║  > On Flow Complete\n")
                render(" ║\n")
                renderCommands(it)
            }
        }
    }

    private fun Ansi.printLogMessages(indent: Int, commandState: CommandState) {
        renderLineStart(indent + 1)
        render("   ")   // Space that a status symbol would normally occupy
        render("@|yellow Log messages:|@\n")

        commandState.logMessages.forEach {
            renderLineStart(indent + 2)
            render("   ")   // Space that a status symbol would normally occupy
            print(fgYellow().a(it).reset().toString()) // Carefully deal with potential for Jansi markdown in the log line
            render("\n")
        }
    }

    private fun Ansi.printInsight(indent: Int, insight: Insight) {
        val color = when (insight.level) {
            Insight.Level.WARNING -> "yellow"
            Insight.Level.INFO -> "cyan"
            else -> "default"
        }
        val level = insight.level.toString().lowercase().replaceFirstChar(Char::uppercase)
        renderLineStart(indent + 1)
        render("   ")   // Space that a status symbol would normally occupy
        render("@|$color $level:|@\n")

        insight.message.split("\n").forEach { paragraph ->
            paragraph.chunkStringByWordCount(12).forEach { chunkedMessage ->
                renderLineStart(indent + 2)
                render("   ")   // Space that a status symbol would normally occupy
                render(chunkedMessage)
                render("\n")
            }
        }
    }

    private fun Ansi.renderLineStart(indent: Int) {
        render(" ║    ")
        repeat(indent) {
            render("  ")
        }
    }

    private fun renderFrame(block: Ansi.() -> Any) {
        renderFrame(StringBuilder().apply {
            val ansi = Ansi().cursor(0, 0)
            ansi.block()
            append(ansi)
        }.toString())
    }

    private fun renderFrame(frame: String) {
        // Clear previous frame
        previousFrame?.let { previousFrame ->
            val lines = previousFrame.lines()
            val height = lines.size
            val width = lines.maxOf { it.length }
            Ansi.ansi().let { ansi ->
                ansi.cursor(0, 0)
                repeat(height) {
                    ansi.render(" ".repeat(width))
                    ansi.render("\n")
                }
                ansi.cursor(0, 0)
                println(ansi)
            }
        }
        print(frame)
        frames.add(createFrame(frame))
        previousFrame = frame
    }

    private fun createFrame(frame: String): Frame {
        val content = frame.encodeBase64()
        return Frame(System.currentTimeMillis() - startTimestamp, content)
    }

    private fun status(status: CommandStatus): String {
        if (useEmojis) {
            return when (status) {
                CommandStatus.COMPLETED -> "✅ "
                CommandStatus.FAILED -> "❌ "
                CommandStatus.RUNNING -> "⏳ "
                CommandStatus.PENDING -> "\uD83D\uDD32 " // 🔲
                CommandStatus.WARNED -> "⚠️ "
                CommandStatus.SKIPPED -> "⚪️ "
            }
        } else {
            return when (status) {
                CommandStatus.COMPLETED -> "+ "
                CommandStatus.FAILED -> "X "
                CommandStatus.RUNNING -> "> "
                CommandStatus.PENDING -> "  "
                CommandStatus.WARNED -> "! "
                CommandStatus.SKIPPED -> "- "
            }
        }
    }

    data class Frame(val timestamp: Long, val content: String)
}

// Helper launcher to play around with presentation
fun main() {
    val view = AnsiResultView("> Press [ENTER] to restart the Flow\n")

    view.setState(
        UiState.Running(
            flowName = "Flow for playing around",
            device = Device.Connected("device", "description", Platform.ANDROID, Device.DeviceType.EMULATOR),
            onFlowStartCommands = listOf(),
            onFlowCompleteCommands = listOf(),
            commands = listOf(
                CommandState(
                    command = MaestroCommand(launchAppCommand = LaunchAppCommand("com.example.example")),
                    status = CommandStatus.COMPLETED,
                    subOnStartCommands = listOf(),
                    subOnCompleteCommands = listOf(),
                ),
                CommandState(
                    command = MaestroCommand(
                        assertWithAICommand = AssertWithAICommand(
                            assertion = "There are no bananas visible",
                            optional = true
                        ),
                    ),
                    status = CommandStatus.WARNED,
                    subOnStartCommands = listOf(),
                    subOnCompleteCommands = listOf(),
                    insight = Insight(
                        message = """
                        |Assertion is false: There are no bananas visible
                        |Reasoning: The screen shows a login screen and no images of bananas are present.
                        """.trimMargin(),
                        level = Insight.Level.WARNING,
                    ),
                ),
                CommandState(
                    command = MaestroCommand(
                        tapOnElement = TapOnElementCommand(
                            selector = ElementSelector("id", "login")
                        ),
                    ),
                    status = CommandStatus.SKIPPED,
                    subOnStartCommands = listOf(),
                    subOnCompleteCommands = listOf(),
                ),
                CommandState(
                    command = MaestroCommand(
                        tapOnElement = TapOnElementCommand(
                            selector = ElementSelector("id", "login"),
                            label = "Use JS value: \${output.some_var}",
                        ),
                    ),
                    status = CommandStatus.RUNNING,
                    subOnStartCommands = listOf(),
                    subOnCompleteCommands = listOf(),
                ),
                CommandState(
                    command = MaestroCommand(tapOnPointV2Command = TapOnPointV2Command(point = "50%, 25%")),
                    status = CommandStatus.PENDING,
                    subOnStartCommands = listOf(),
                    subOnCompleteCommands = listOf(),
                ),
                CommandState(
                    command = MaestroCommand(tapOnPointV2Command = TapOnPointV2Command(point = "50%, 25%")),
                    status = CommandStatus.FAILED,
                    subOnStartCommands = listOf(),
                    subOnCompleteCommands = listOf(),
                    insight = Insight("This is insight message", Insight.Level.NONE),
                ),
                CommandState(
                    command = MaestroCommand(tapOnPointV2Command = TapOnPointV2Command(point = "50%, 25%")),
                    status = CommandStatus.FAILED,
                    subOnStartCommands = listOf(),
                    subOnCompleteCommands = listOf(),
                    insight = Insight("This is an error message", Insight.Level.INFO),
                ),
            )
        )
    )
}
