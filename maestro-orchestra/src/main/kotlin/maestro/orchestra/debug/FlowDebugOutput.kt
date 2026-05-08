package maestro.orchestra.debug

import maestro.MaestroException
import maestro.TreeNode
import maestro.orchestra.MaestroCommand
import java.io.File
import java.util.IdentityHashMap

data class CommandDebugMetadata(
    var status: CommandStatus? = null,
    var timestamp: Long? = null,
    var duration: Long? = null,
    var error: Throwable? = null,
    var hierarchy: TreeNode? = null,
    var sequenceNumber: Int = 0,
    var evaluatedCommand: MaestroCommand? = null,
) {
    fun calculateDuration() {
        if (timestamp != null) {
            duration = System.currentTimeMillis() - timestamp!!
        }
    }
}

data class FlowDebugOutput(
    val commands: IdentityHashMap<MaestroCommand, CommandDebugMetadata> = IdentityHashMap<MaestroCommand, CommandDebugMetadata>(),
    val screenshots: MutableList<Screenshot> = mutableListOf(),
    var exception: MaestroException? = null,
) {
    data class Screenshot(
        val screenshot: File,
        val timestamp: Long,
        val status: CommandStatus,
    )
}
