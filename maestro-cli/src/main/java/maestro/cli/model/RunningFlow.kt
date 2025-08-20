package maestro.cli.model

import kotlin.time.Duration

data class RunningFlows(
    val flows: List<RunningFlow>,
    val duration: Duration?
)

data class RunningFlow(
    val name: String,
    val status: FlowStatus,
    val duration: Duration? = null,
    val reported: Boolean = false
)
