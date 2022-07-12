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

package conductor.cli.runner

import org.fusesource.jansi.Ansi

class ResultView {

    private var previousFrame: String? = null

    fun setState(state: UiState) {
        when (state) {
            is UiState.Running -> renderRunningState(state)
            is UiState.Error -> renderErrorState(state)
        }
    }

    private fun renderErrorState(state: UiState.Error) = renderFrame {
        fgRed()
        render(state.message)
    }

    private fun renderRunningState(state: UiState.Running) = renderFrame {
        val statusColumnWidth = 3
        state.commands.forEach {
            val statusSymbol = status(it.status)
            fgDefault()
            render(statusSymbol)
            render(String(CharArray(statusColumnWidth - statusSymbol.length) { ' ' }))
            render(it.command.description())
            render("\n")
        }
    }

    private fun status(status: CommandStatus): String {
        return when (status) {
            CommandStatus.COMPLETED -> "✅"
            CommandStatus.FAILED -> "❌"
            CommandStatus.RUNNING -> "⏳"
            CommandStatus.PENDING -> "\uD83D\uDD32"
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
        println(frame)
        previousFrame = frame
    }

    sealed class UiState {

        data class Error(val message: String) : UiState()

        data class Running(
            val commands: List<CommandState>,
        ) : UiState()

    }
}