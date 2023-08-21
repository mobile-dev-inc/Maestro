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

import io.ktor.client.utils.EmptyContent.status
import maestro.Maestro
import maestro.MaestroException
import maestro.cli.device.Device
import maestro.cli.report.CommandDebugMetadata
import maestro.cli.report.FlowDebugMetadata
import maestro.cli.report.ScreenshotDebugMetadata
import maestro.cli.runner.resultview.ResultView
import maestro.cli.runner.resultview.UiState
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.CompositeCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.Orchestra
import maestro.orchestra.OrchestraAppState
import maestro.orchestra.yaml.YamlCommandReader
import maestro.utils.Insight
import org.slf4j.LoggerFactory
import java.io.File
import java.util.IdentityHashMap

object MaestroCommandRunner {

    private val logger = LoggerFactory.getLogger(MaestroCommandRunner::class.java)

    fun runCommands(
        maestro: Maestro,
        device: Device?,
        view: ResultView,
        commands: List<MaestroCommand>,
        debug: FlowDebugMetadata
    ): Result {
        val config = YamlCommandReader.getConfig(commands)
        val initFlow = config?.initFlow
        val onFlowComplete = config?.onFlowComplete
        val onFlowStart = config?.onFlowStart

        val commandStatuses = IdentityHashMap<MaestroCommand, CommandStatus>()
        val commandMetadata = IdentityHashMap<MaestroCommand, Orchestra.CommandMetadata>()

        // debug
        val debugCommands = debug.commands
        val debugScreenshots = debug.screenshots

        fun takeDebugScreenshot(status: CommandStatus): File? {
            val containsFailed = debugScreenshots.any { it.status == CommandStatus.FAILED }

            // Avoids duplicate failed images from parent commands
            if (containsFailed && status == CommandStatus.FAILED) {
                return null
            }

            val result = kotlin.runCatching {
                val out = File.createTempFile("screenshot-${System.currentTimeMillis()}", ".png")
                    .also { it.deleteOnExit() } // save to another dir before exiting
                maestro.takeScreenshot(out, false)
                debugScreenshots.add(
                    ScreenshotDebugMetadata(
                        screenshot = out,
                        timestamp = System.currentTimeMillis(),
                        status = status
                    )
                )
                out
            }

            return result.getOrNull()
        }

        fun refreshUi() {
            view.setState(
                UiState.Running(
                    device = device,
                    initCommands = toCommandStates(
                        initFlow?.commands ?: emptyList(),
                        commandStatuses,
                        commandMetadata
                    ),
                    onFlowStartCommands = toCommandStates(
                        onFlowStart?.commands ?: emptyList(),
                        commandStatuses,
                        commandMetadata
                    ),
                    onFlowCompleteCommands = toCommandStates(
                        onFlowComplete?.commands ?: emptyList(),
                        commandStatuses,
                        commandMetadata
                    ),
                    commands = toCommandStates(
                        commands,
                        commandStatuses,
                        commandMetadata
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
                debugCommands[command] = CommandDebugMetadata(
                    timestamp = System.currentTimeMillis(),
                    status = CommandStatus.RUNNING
                )

                refreshUi()
            },
            onCommandComplete = { _, command ->
                logger.info("${command.description()} COMPLETED")
                commandStatuses[command] = CommandStatus.COMPLETED
                debugCommands[command]?.let {
                    it.status = CommandStatus.COMPLETED
                    it.calculateDuration()
                }
                refreshUi()
            },
            onCommandFailed = { _, command, e ->
                debugCommands[command]?.let {
                    it.status = CommandStatus.FAILED
                    it.calculateDuration()
                    it.error = e
                }

                takeDebugScreenshot(CommandStatus.FAILED)

                if (e !is MaestroException) {
                    throw e
                } else {
                    debug.exception = e
                }

                logger.info("${command.description()} FAILED")
                commandStatuses[command] = CommandStatus.FAILED
                refreshUi()
                Orchestra.ErrorResolution.FAIL
            },
            onCommandSkipped = { _, command ->
                logger.info("${command.description()} SKIPPED")
                commandStatuses[command] = CommandStatus.SKIPPED
                debugCommands[command]?.let {
                    it.status = CommandStatus.SKIPPED
                }
                refreshUi()
            },
            onCommandReset = { command ->
                logger.info("${command.description()} PENDING")
                commandStatuses[command] = CommandStatus.PENDING
                debugCommands[command]?.let {
                    it.status = CommandStatus.PENDING
                }
                refreshUi()
            },
            onCommandMetadataUpdate = { command, metadata ->
                logger.info("${command.description()} metadata $metadata")
                commandMetadata[command] = metadata
                refreshUi()
            },
        )

        val flowSuccess = orchestra.runFlow(commands)

        return Result(flowSuccess = flowSuccess, cachedAppState = null)
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
                    subOnStartCommands = (command.asCommand() as? CompositeCommand)
                        ?.config()
                        ?.onFlowStart
                        ?.let { toCommandStates(it.commands, commandStatuses, commandMetadata) },
                    subOnCompleteCommands = (command.asCommand() as? CompositeCommand)
                        ?.config()
                        ?.onFlowComplete
                        ?.let { toCommandStates(it.commands, commandStatuses, commandMetadata) },
                    status = commandStatuses[command] ?: CommandStatus.PENDING,
                    numberOfRuns = commandMetadata[command]?.numberOfRuns,
                    subCommands = (command.asCommand() as? CompositeCommand)
                        ?.subCommands()
                        ?.let { toCommandStates(it, commandStatuses, commandMetadata) },
                    logMessages = commandMetadata[command]?.logMessages ?: emptyList(),
                    insight = commandMetadata[command]?.insight ?: Insight("", Insight.Level.NONE)
                )
            }
    }

    data class Result(
        val flowSuccess: Boolean,
        val cachedAppState: OrchestraAppState?
    )
}

