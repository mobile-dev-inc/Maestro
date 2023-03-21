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
import maestro.MaestroException
import maestro.debuglog.DebugLogStore
import maestro.cli.device.Device
import maestro.cli.runner.resultview.ResultView
import maestro.cli.runner.resultview.UiState
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.CompositeCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.OrchestraAppState
import maestro.orchestra.yaml.YamlCommandReader
import java.util.IdentityHashMap

object MaestroCommandRunner {

    private val logger = DebugLogStore.loggerFor(MaestroCommand::class.java)

    fun runCommands(
        maestro: Maestro,
        device: Device?,
        view: ResultView,
        commands: List<MaestroCommand>,
        cachedAppState: OrchestraAppState?,
    ): Result {
        val initFlow = YamlCommandReader.getConfig(commands)?.initFlow

        val commandStatuses = IdentityHashMap<MaestroCommand, CommandStatus>()
        val commandMetadata = IdentityHashMap<MaestroCommand, Orchestra.CommandMetadata>()

        fun refreshUi() {
            view.setState(
                UiState.Running(
                    device = device,
                    initCommands = toCommandStates(
                        initFlow?.commands ?: emptyList(),
                        commandStatuses,
                        commandMetadata,
                    ),
                    commands = toCommandStates(
                        commands,
                        commandStatuses,
                        commandMetadata,
                    )
                )
            )
        }

        refreshUi()

        val orchestra = Orchestra(
            maestro,
            onCommandStart = { _, command ->
                logger.info("${command.description()} RUNNING")
                commandStatuses[command] = CommandStatus.RUNNING
                refreshUi()
            },
            onCommandComplete = { _, command ->
                logger.info("${command.description()} COMPLETED")
                commandStatuses[command] = CommandStatus.COMPLETED
                refreshUi()
            },
            onCommandFailed = { _, command, e ->
                if (e !is MaestroException) {
                    throw e
                }

                logger.info("${command.description()} FAILED")
                commandStatuses[command] = CommandStatus.FAILED
                refreshUi()
                Orchestra.ErrorResolution.FAIL
            },
            onCommandSkipped = { _, command ->
                logger.info("${command.description()} SKIPPED")
                commandStatuses[command] = CommandStatus.SKIPPED
                refreshUi()
            },
            onCommandReset = { command ->
                logger.info("${command.description()} PENDING")
                commandStatuses[command] = CommandStatus.PENDING
                refreshUi()
            },
            onCommandMetadataUpdate = { command, metadata ->
                logger.info("${command.description()} metadata $metadata")
                commandMetadata[command] = metadata
                refreshUi()
            },
        )

        val cachedState = if (cachedAppState == null) {
            initFlow?.let {
                orchestra.runInitFlow(it) ?: return Result(flowSuccess = false, cachedAppState = null)
            }
        } else {
            initFlow?.commands?.forEach { commandStatuses[it] = CommandStatus.COMPLETED }
            cachedAppState
        }

        val flowSuccess = orchestra.runFlow(commands, cachedState)

        return Result(flowSuccess = flowSuccess, cachedAppState = cachedState)
    }

    private fun toCommandStates(
        commands: List<MaestroCommand>,
        commandStatuses: MutableMap<MaestroCommand, CommandStatus>,
        commandMetadata: IdentityHashMap<MaestroCommand, Orchestra.CommandMetadata>,
    ): List<CommandState> {
        return commands
            // Don't render configuration commands
            .filter { it.asCommand() !is ApplyConfigurationCommand }
            .mapIndexed { _, command ->
                CommandState(
                    command = commandMetadata[command]?.evaluatedCommand ?: command,
                    status = commandStatuses[command] ?: CommandStatus.PENDING,
                    numberOfRuns = commandMetadata[command]?.numberOfRuns,
                    subCommands = (command.asCommand() as? CompositeCommand)
                        ?.subCommands()
                        ?.let { toCommandStates(it, commandStatuses, commandMetadata) },
                    logMessages = commandMetadata[command]?.logMessages ?: emptyList(),
                )
            }
    }

    data class Result(
        val flowSuccess: Boolean,
        val cachedAppState: OrchestraAppState?,
    )
}
