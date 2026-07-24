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
    /** Nesting level: 0 at the flow top, +1 per runFlow/repeat/retry. The worker's subIndex. */
    var depth: Int = 0,
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
    /** Identity-keyed, last-write-wins: backs in-flight attribution. See [executedSteps] for the per-execution record. */
    val commands: IdentityHashMap<MaestroCommand, CommandDebugMetadata> = IdentityHashMap<MaestroCommand, CommandDebugMetadata>(),
    /** One entry per execution, in order — a retry/repeat attempt is its own entry. Serialized to commands.json. */
    val executedSteps: MutableList<CommandDebugMetadata> = mutableListOf(),
    var exception: MaestroException? = null,
)
