package maestro.orchestra.debug

import com.fasterxml.jackson.annotation.JsonIgnore
import maestro.MaestroException
import maestro.orchestra.ArtifactKind
import maestro.orchestra.MaestroCommand
import java.util.IdentityHashMap

/** One artifact a command produced: its kind plus the run-root-relative path. */
data class CommandArtifact(val type: ArtifactKind, val path: String)

data class CommandDebugMetadata(
    var status: CommandStatus? = null,
    var timestamp: Long? = null,
    var duration: Long? = null,
    var error: Throwable? = null,
    var sequenceNumber: Int = 0,
    var evaluatedCommand: MaestroCommand? = null,
    val artifacts: MutableList<CommandArtifact> = mutableListOf(),
    /** Back-reference used to attribute collector records; excluded from commands.json (it keys this map). */
    @field:JsonIgnore var command: MaestroCommand? = null,
) {
    fun calculateDuration() {
        if (timestamp != null) {
            duration = System.currentTimeMillis() - timestamp!!
        }
    }
}

data class FlowDebugOutput(
    val commands: IdentityHashMap<MaestroCommand, CommandDebugMetadata> = IdentityHashMap<MaestroCommand, CommandDebugMetadata>(),
    var exception: MaestroException? = null,
)
