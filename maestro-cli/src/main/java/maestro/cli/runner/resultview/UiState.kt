package maestro.cli.runner.resultview

import maestro.cli.device.Device
import maestro.cli.runner.CommandState

sealed class UiState {

    data class Error(val message: String) : UiState()

    data class Running(
        val device: Device?,
        val initCommands: List<CommandState>,
        val commands: List<CommandState>,
    ) : UiState()

}
