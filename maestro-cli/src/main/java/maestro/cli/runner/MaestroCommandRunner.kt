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
import maestro.orchestra.OrchestraAppState
import maestro.orchestra.yaml.YamlCommandReader
import java.util.IdentityHashMap

object MaestroCommandRunner {

    fun runCommands(
        maestro: Maestro,
        view: ResultView,
        commands: List<MaestroCommand>,
        env: Map<String, String>,
        cachedAppState: OrchestraAppState?,
    ): Result {
        val initFlow = YamlCommandReader.getConfig(commands)?.initFlow

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
                                status = commandStatuses[command] ?: CommandStatus.PENDING
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

        val orchestra = Orchestra(
            maestro,
            onCommandStart = { _, command ->
                commandStatuses[command] = CommandStatus.RUNNING
                refreshUi()
            },
            onCommandComplete = { _, command ->
                commandStatuses[command] = CommandStatus.COMPLETED
                refreshUi()
            },
            onCommandFailed = { _, command, _ ->
                commandStatuses[command] = CommandStatus.FAILED
                refreshUi()
            },
        )

        val cachedState = if (cachedAppState == null) {
            initFlow?.let {
                orchestra.runInitFlow(it, env = env) ?: return Result(flowSuccess = false, cachedAppState = null)
            }
        } else {
            initFlow?.commands?.forEach { commandStatuses[it] = CommandStatus.COMPLETED }
            cachedAppState
        }

        val flowSuccess = orchestra.runFlow(commands, cachedState, env = env)

        return Result(flowSuccess = flowSuccess, cachedAppState = cachedState)
    }

    data class Result(
        val flowSuccess: Boolean,
        val cachedAppState: OrchestraAppState?,
    )
}