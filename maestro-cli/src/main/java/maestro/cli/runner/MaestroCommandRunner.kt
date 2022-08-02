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

package maestro.cli.runner

import maestro.Maestro
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import java.util.IdentityHashMap

object MaestroCommandRunner {

    fun runCommands(
        maestro: Maestro,
        view: ResultView,
        initCommands: List<MaestroCommand>,
        commands: List<MaestroCommand>,
        skipInitFlow: Boolean,
    ): Result {
        val initCommandStatuses = IdentityHashMap<MaestroCommand, CommandStatus>()
        val commandStatuses = IdentityHashMap<MaestroCommand, CommandStatus>()

        fun refreshUi() {
            view.setState(
                ResultView.UiState.Running(
                    initCommands = initCommands
                        .mapIndexed { _, command ->
                            CommandState(
                                command = command,
                                status = initCommandStatuses[command] ?: CommandStatus.PENDING
                            )
                        },
                    commands = commands
                        // Don't render configuration commands
                        .filter { it.applyConfigurationCommand == null }
                        .mapIndexed { _, command ->
                            CommandState(
                                command = command,
                                status = commandStatuses[command] ?: CommandStatus.PENDING
                            )
                        },
                )
            )
        }

        refreshUi()

        var success = true

        fun executeCommands(commands: List<MaestroCommand>, statuses: MutableMap<MaestroCommand, CommandStatus>) {
            Orchestra(
                maestro,
                onCommandStart = { _, command ->
                    statuses[command] = CommandStatus.RUNNING
                    refreshUi()
                },
                onCommandComplete = { _, command ->
                    statuses[command] = CommandStatus.COMPLETED
                    refreshUi()
                },
                onCommandFailed = { _, command, _ ->
                    statuses[command] = CommandStatus.FAILED
                    refreshUi()
                    success = false
                },
            ).executeCommands(commands)
        }

        if (skipInitFlow) {
            initCommands.forEach { initCommandStatuses[it] = CommandStatus.COMPLETED }
        } else {
            executeCommands(initCommands, initCommandStatuses)
        }

        if (!success) return Result(initFlowSuccess = false, flowSuccess = false)

        executeCommands(commands, commandStatuses)

        return Result(initFlowSuccess = true, flowSuccess = success)
    }

    data class Result(
        val initFlowSuccess: Boolean,
        val flowSuccess: Boolean
    )
}