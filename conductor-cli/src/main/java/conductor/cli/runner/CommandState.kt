package conductor.cli.runner

import conductor.orchestra.ConductorCommand

data class CommandState(
    val status: CommandStatus,
    val command: ConductorCommand,
)