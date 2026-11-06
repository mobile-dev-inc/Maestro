package maestro.cli.runner

import maestro.cli.report.EventStream
import maestro.orchestra.MaestroCommand
import maestro.orchestra.debug.CommandOutcome
import maestro.orchestra.debug.OrchestraListener
import java.util.IdentityHashMap

/** Emits the events of one flow run to an [EventStream]; shard and device are fixed for the run. */
class EventStreamListener(
    private val stream: EventStream,
    private val flow: String,
    private val shardIndex: Int?,
    private val deviceId: String?,
) : OrchestraListener {

    private class CommandPosition(val sequenceNumber: Int, val depth: Int)

    // Keyed by identity: the same command object can run again in repeat/retry, and the latest start should win.
    private val positions = IdentityHashMap<MaestroCommand, CommandPosition>()

    override fun onFlowStart() {
        stream.emit(common("flow.started"))
    }

    override fun onCommandStart(cmd: MaestroCommand, sequenceNumber: Int, depth: Int) {
        positions[cmd] = CommandPosition(sequenceNumber, depth)
        stream.emit(
            common("command.started").apply {
                put("sequenceNumber", sequenceNumber)
                put("depth", depth)
                put("command", cmd.description())
            }
        )
    }

    override fun onCommandFinished(
        cmd: MaestroCommand,
        outcome: CommandOutcome,
        startedAt: Long,
        finishedAt: Long,
    ) {
        val position = positions[cmd]
        stream.emit(
            common("command.finished").apply {
                put("sequenceNumber", position?.sequenceNumber)
                put("depth", position?.depth)
                put("command", cmd.description())
                put("status", outcome.toCommandStatus().name)
                put("duration", finishedAt - startedAt)
                put("error", (outcome as? CommandOutcome.Failed)?.error?.message)
            }
        )
    }

    /** Called by the runner once it knows the result, since [onFlowEnd] does not carry it. */
    fun flowFinished(success: Boolean, duration: Long, error: String?) {
        stream.emit(
            common("flow.finished").apply {
                put("status", if (success) "SUCCESS" else "ERROR")
                put("duration", duration)
                put("error", if (success) null else error)
            }
        )
    }

    private fun common(type: String): LinkedHashMap<String, Any?> = linkedMapOf(
        "type" to type,
        "timestamp" to System.currentTimeMillis(),
        "flow" to flow,
        "shardIndex" to shardIndex,
        "deviceId" to deviceId,
    )
}
