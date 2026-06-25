package maestro.orchestra.debug

import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import okio.Buffer

/**
 * Observer of Orchestra's per-flow and per-command lifecycle. All methods
 * default to no-ops.
 */
interface OrchestraListener {

    fun onFlowStart() = Unit

    /**
     * @param sequenceNumber monotonic across the whole flow, nested commands included.
     * @param depth nesting level: 0 at the flow top, +1 per runFlow/repeat/retry.
     */
    fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int, depth: Int = 0) = Unit

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

    /** Screenshot an AI assertion analyzed, with the defect count it found. */
    fun onAIArtifactGenerated(screenshot: Buffer, defectCount: Int) = Unit

    fun onFlowEnd() = Unit
}

/** Terminal outcome of a command, as surfaced to listeners. */
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
