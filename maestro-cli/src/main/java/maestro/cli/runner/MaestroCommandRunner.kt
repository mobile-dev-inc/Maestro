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
import maestro.orchestra.MaestroInitFlow
import maestro.orchestra.Orchestra
import java.io.File
import java.util.IdentityHashMap

object MaestroCommandRunner {

    fun runCommands(
        maestro: Maestro,
        view: ResultView,
        initFlow: MaestroInitFlow?,
        commands: List<MaestroCommand>,
        cachedAppState: File?,
    ): Result {
        val initCommandStatuses = IdentityHashMap<MaestroCommand, CommandStatus>()
        val commandStatuses = IdentityHashMap<MaestroCommand, CommandStatus>()

        fun refreshUi() {
            view.setState(
                ResultView.UiState.Running(
                    initCommands = (initFlow?.commands ?: emptyList())
                        // Don't render configuration commands
                        .filter { it.applyConfigurationCommand == null }
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

        fun executeCommands(commands: List<MaestroCommand>, statuses: MutableMap<MaestroCommand, CommandStatus>): Boolean {
            var success = true
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
            return success
        }

        val initAppState: File? = if (initFlow == null) {
            null
        } else {
            val state = if (cachedAppState == null) {
                val initFlowSuccess = executeCommands(initFlow.commands, initCommandStatuses)
                if (!initFlowSuccess) return Result(flowSuccess = false, cachedAppState = null)

                val appStateFile = File("/Users/leland/Downloads/state.zip")
                maestro.stopApp(initFlow.appId)
                maestro.pullAppState(initFlow.appId, appStateFile)
                appStateFile
            } else {
                cachedAppState
            }

            initFlow.commands.forEach { initCommandStatuses[it] = CommandStatus.COMPLETED }

            maestro.clearAppState(initFlow.appId)
            maestro.pushAppState(initFlow.appId, state)

            state
        }

        val flowSuccess = executeCommands(commands, commandStatuses)

        return Result(flowSuccess = flowSuccess, cachedAppState = initAppState)
    }

    data class Result(
        val flowSuccess: Boolean,
        val cachedAppState: File?,
    )
}