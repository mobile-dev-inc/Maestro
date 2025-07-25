package maestro.cli.model

import maestro.cli.api.UploadStatus
import kotlin.time.Duration

data class RunningFlows(
    val flows: MutableSet<RunningFlow> = mutableSetOf(),
    var duration: Duration? = null
)

data class RunningFlow(
    val name: String,
    var status: FlowStatus? = null,
    var startTime: Long? = null,
    var duration: Duration? = null,
    var reported: Boolean = false
)
