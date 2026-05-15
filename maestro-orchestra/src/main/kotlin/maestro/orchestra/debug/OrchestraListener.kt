package maestro.orchestra.debug

import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra

/**
 * Observer of Orchestra's per-flow and per-command lifecycle. Both Maestro's
 * internal [ArtifactsGenerator] and consumer-supplied listeners (CLI's console
 * logging, worker's API reporting, Studio's SSE push) implement this single
 * interface, so Orchestra has one notification mechanism for all observers.
 *
 * All methods default to no-ops; implementers override only what they care about.
 */
interface OrchestraListener {

    /** Called once at the start of [Orchestra.runFlow], before any command runs. */
    fun onFlowStart() = Unit

    /**
     * Called when a command is about to execute.
     *
     * @param sequenceNumber Monotonic counter across the whole flow (top-level
     *   commands and commands nested inside composites are both numbered).
     *   Distinct from Orchestra's per-call-frame `index`, which restarts at 0
     *   for each nested composite.
     */
    fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int) = Unit

    /**
     * Called when a command reaches a terminal state (completed, failed,
     * skipped, or warned).
     *
     * @param startedAt epoch millis at the moment [onCommandStart] fired for
     *   this command.
     * @param finishedAt epoch millis at the moment the terminal state was
     *   reached.
     */
    fun onCommandFinished(
        cmd: MaestroCommand,
        outcome: CommandOutcome,
        startedAt: Long,
        finishedAt: Long,
    ) = Unit

    /**
     * Called when a previously-executed command is reset to PENDING (CLI's
     * interactive re-run feature). Resets any per-command state implementers
     * may have accumulated.
     */
    fun onCommandReset(cmd: MaestroCommand) = Unit

    /**
     * Called when Orchestra collects additional metadata about a command
     * (evaluatedCommand, logMessages, aiReasoning, etc.). May fire multiple
     * times for the same command.
     */
    fun onCommandMetadataUpdate(cmd: MaestroCommand, metadata: Orchestra.CommandMetadata) = Unit

    /** Called once at the end of [Orchestra.runFlow], regardless of outcome. */
    fun onFlowEnd() = Unit
}

/**
 * Terminal outcome of a command execution. Surfaced to listeners via
 * [OrchestraListener.onCommandFinished] so they get an exhaustive `when` over
 * a sealed type instead of having to pattern-match across multiple callbacks.
 */
sealed class CommandOutcome {
    object Completed : CommandOutcome()
    object Skipped : CommandOutcome()
    object Warned : CommandOutcome()
    data class Failed(val error: Throwable) : CommandOutcome()

    /** Convenience for implementers that need to write the legacy [CommandStatus] string. */
    fun toCommandStatus(): CommandStatus = when (this) {
        is Completed -> CommandStatus.COMPLETED
        is Skipped -> CommandStatus.SKIPPED
        is Warned -> CommandStatus.WARNED
        is Failed -> CommandStatus.FAILED
    }
}
