package maestro.orchestra.debug

import maestro.orchestra.ArtifactKind
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra

/**
 * Observer of Orchestra's per-flow and per-command lifecycle. Implemented by the
 * internal [ArtifactsGenerator] and consumer listeners (CLI console, worker API
 * reporting, Studio SSE). All methods default to no-ops.
 */
interface OrchestraListener {

    fun onFlowStart() = Unit

    /**
     * @param sequenceNumber monotonic counter across the whole flow (nested
     *   commands included). Distinct from Orchestra's per-frame `index`.
     */
    fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int) = Unit

    /** @param startedAt/[finishedAt] epoch millis bracketing the command. */
    fun onCommandFinished(
        cmd: MaestroCommand,
        outcome: CommandOutcome,
        startedAt: Long,
        finishedAt: Long,
    ) = Unit

    /** Command reset to PENDING (CLI interactive re-run). */
    fun onCommandReset(cmd: MaestroCommand) = Unit

    /** Extra command metadata (evaluatedCommand, logMessages, …); may fire repeatedly. */
    fun onCommandMetadataUpdate(cmd: MaestroCommand, metadata: Orchestra.CommandMetadata) = Unit

    /** The currently-running command wrote [relativePath] (run-root-relative) into the bundle. */
    fun onCommandArtifact(kind: ArtifactKind, relativePath: String) = Unit

    fun onFlowEnd() = Unit
}

/**
 * Terminal outcome of a command, surfaced to listeners as a sealed type for an
 * exhaustive `when`.
 */
sealed class CommandOutcome {
    object Completed : CommandOutcome()
    object Skipped : CommandOutcome()
    object Warned : CommandOutcome()
    data class Failed(val error: Throwable) : CommandOutcome()

    fun toCommandStatus(): CommandStatus = when (this) {
        is Completed -> CommandStatus.COMPLETED
        is Skipped -> CommandStatus.SKIPPED
        is Warned -> CommandStatus.WARNED
        is Failed -> CommandStatus.FAILED
    }
}
