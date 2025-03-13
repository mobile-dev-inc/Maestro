package maestro.cli.runner.resultview

import maestro.device.Device
import maestro.cli.runner.CommandState

sealed class UiState {

    data class Error(val message: String) : UiState()

    data class Running(
        val flowName: String,
        val device: Device? = null,
        val onFlowStartCommands: List<CommandState> = emptyList(),
        val onFlowCompleteCommands: List<CommandState> = emptyList(),
        val commands: List<CommandState>,
    ) : UiState()

}
