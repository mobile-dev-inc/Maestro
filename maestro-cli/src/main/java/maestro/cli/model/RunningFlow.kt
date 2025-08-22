package maestro.cli.model

import kotlin.time.Duration

data class RunningFlows(
    val flows: List<RunningFlow>,
    val duration: Duration?,
    val startTime: Long?
)

data class RunningFlow(
    val name: String,
    val status: FlowStatus,
    val duration: Duration? = null,
    val startTime: Long? = null,
    val reported: Boolean = false
)
