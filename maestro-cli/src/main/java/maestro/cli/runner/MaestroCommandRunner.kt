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
import maestro.device.Device
import maestro.cli.report.SingleScreenFlowAIOutput
import maestro.cli.report.CommandDebugMetadata
import maestro.cli.report.FlowAIOutput
import maestro.cli.report.FlowDebugOutput
import maestro.cli.runner.resultview.ResultView
import maestro.cli.runner.resultview.UiState
import maestro.orchestra.ApplyConfigurationCommand
import maestro.orchestra.CompositeCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.yaml.YamlCommandReader
import maestro.utils.CliInsights
import org.slf4j.LoggerFactory
import java.util.IdentityHashMap
import maestro.cli.util.ScreenshotUtils
import maestro.utils.Insight

/**
 * Knows how to run a list of Maestro commands and update the UI.
 *
 * Should not know what a "flow" is (apart from knowing a name, for display purposes).
 */
object MaestroCommandRunner {

    private val logger = LoggerFactory.getLogger(MaestroCommandRunner::class.java)

    fun runCommands(
        flowName: String,
        maestro: Maestro,
        device: Device?,
        view: ResultView,
        commands: List<MaestroCommand>,
        debugOutput: FlowDebugOutput,
        aiOutput: FlowAIOutput,
        analyze: Boolean = false
    ): Boolean {
        val config = YamlCommandReader.getConfig(commands)
        val onFlowComplete = config?.onFlowComplete
        val onFlowStart = config?.onFlowStart

        val commandStatuses = IdentityHashMap<MaestroCommand, CommandStatus>()
        val commandMetadata = IdentityHashMap<MaestroCommand, Orchestra.CommandMetadata>()

        fun refreshUi() {
            view.setState(
                UiState.Running(
                    flowName = flowName,
                    device = device,
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
        if (analyze) {
            ScreenshotUtils.takeDebugScreenshotByCommand(maestro, debugOutput, CommandStatus.PENDING)
        }

        val orchestra = Orchestra(
            maestro = maestro,
            insights = CliInsights,
            onCommandStart = { _, command ->
                logger.info("${command.description()} RUNNING")
                commandStatuses[command] = CommandStatus.RUNNING
                debugOutput.commands[command] = CommandDebugMetadata(
                    timestamp = System.currentTimeMillis(),
                    status = CommandStatus.RUNNING
                )

                refreshUi()
            },
            onCommandComplete = { _, command ->
                logger.info("${command.description()} COMPLETED")
                commandStatuses[command] = CommandStatus.COMPLETED
                if (analyze) {
                    ScreenshotUtils.takeDebugScreenshotByCommand(maestro, debugOutput, CommandStatus.COMPLETED)
                }

                debugOutput.commands[command]?.apply {
                    status = CommandStatus.COMPLETED
                    calculateDuration()
                }
                refreshUi()
            },
            onCommandFailed = { _, command, e ->
                debugOutput.commands[command]?.apply {
                    status = CommandStatus.FAILED
                    calculateDuration()
                    error = e
                }

                ScreenshotUtils.takeDebugScreenshot(maestro, debugOutput, CommandStatus.FAILED)

                if (e !is MaestroException) {
                    throw e
                } else {
                    debugOutput.exception = e
                }

                logger.info("${command.description()} FAILED")
                commandStatuses[command] = CommandStatus.FAILED
                refreshUi()
                Orchestra.ErrorResolution.FAIL
            },
            onCommandSkipped = { _, command ->
                logger.info("${command.description()} SKIPPED")
                commandStatuses[command] = CommandStatus.SKIPPED
                debugOutput.commands[command]?.apply {
                    status = CommandStatus.SKIPPED
                }
                refreshUi()
            },
            onCommandWarned = { _, command ->
                logger.info("${command.description()} WARNED")
                commandStatuses[command] = CommandStatus.WARNED
                debugOutput.commands[command]?.apply {
                    status = CommandStatus.WARNED
                }

                ScreenshotUtils.takeDebugScreenshot(maestro, debugOutput, CommandStatus.WARNED)

                refreshUi()
            },
            onCommandReset = { command ->
                logger.info("${command.description()} PENDING")
                commandStatuses[command] = CommandStatus.PENDING
                debugOutput.commands[command]?.apply {
                    status = CommandStatus.PENDING
                }
                refreshUi()
            },
            onCommandMetadataUpdate = { command, metadata ->
                logger.info("${command.description()} metadata $metadata")
                commandMetadata[command] = metadata
                refreshUi()
            },
            onCommandGeneratedOutput = { command, defects, screenshot ->
                logger.info("${command.description()} generated output")
                val screenshotPath = ScreenshotUtils.writeAIscreenshot(screenshot)
                aiOutput.screenOutputs.add(
                    SingleScreenFlowAIOutput(
                        screenshotPath = screenshotPath,
                        defects = defects,
                    )
                )
            }
        )

        val flowSuccess = orchestra.runFlow(commands)
        return flowSuccess
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
}
