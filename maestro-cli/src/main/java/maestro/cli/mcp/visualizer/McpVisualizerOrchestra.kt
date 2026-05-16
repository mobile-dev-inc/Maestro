package maestro.cli.mcp.visualizer

import maestro.Maestro
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object McpVisualizerOrchestra {

    // Process-scoped so callIds stay unique as the frontend appends commands across runs;
    // an AtomicInteger retains nothing so this isn't a leak.
    private val nextCommandId = AtomicInteger()

    fun create(maestro: Maestro): Orchestra {
        // Identity-keyed: the same MaestroCommand instance flows from runFlow's input list
        // through to onCommandStart/Complete, so pending-then-runtime events upsert into
        // the same visualizer row. Scoped to one create() call so command instances (and
        // their retained YAML sources) can be GC'd once the flow finishes.
        val commandIds = IdentityHashMap<MaestroCommand, Int>()
        fun MaestroCommand.id(): String =
            commandIds.computeIfAbsent(this) { nextCommandId.incrementAndGet() }.toString()

        // Orchestra fires onCommandStart/Complete for nested subflow commands too. We
        // suppress those because the outer `runFlow:` command's yaml already inlines its
        // nested commands — surfacing each sub-step would clutter the log without adding
        // information. Track depth via a stack: top frame = isOuter for the most recently
        // started command; only outer-most frames publish events.
        val depth = ArrayDeque<Boolean>()

        fun publishCommand(status: CommandStatus, command: MaestroCommand, error: Throwable? = null) {
            McpVisualizerEvents.publish(
                VisualizerEvent.Command(
                    status = status,
                    callId = command.id(),
                    yaml = command.sourceInfo?.let { it.source.substring(it.startOffset, it.endOffset) },
                    errorMessage = error?.message,
                )
            )
        }

        return Orchestra(
            maestro = maestro,
            onFlowStart = {
                depth.clear()
            },
            onCommandStart = { _, command ->
                val isOuter = depth.isEmpty()
                depth.addLast(isOuter)
                if (isOuter) publishCommand(CommandStatus.STARTED, command)
            },
            onCommandComplete = { _, command ->
                val wasOuter = depth.removeLast()
                if (wasOuter) publishCommand(CommandStatus.COMPLETED, command)
            },
            onCommandWarned = { _, command ->
                val wasOuter = depth.removeLast()
                if (wasOuter) publishCommand(CommandStatus.WARNED, command)
            },
            onCommandSkipped = { _, command ->
                // onCommandSkipped fires after onCommandStart inside subflows; outside
                // subflows it can fire without a Start (when a top-level command is
                // pre-skipped via `when:`). Pop only if our stack has a frame for it.
                val wasOuter = if (depth.isNotEmpty()) depth.removeLast() else true
                if (wasOuter) publishCommand(CommandStatus.SKIPPED, command)
            },
            onCommandFailed = { _, command, error ->
                val wasOuter = if (depth.isNotEmpty()) depth.removeLast() else true
                if (wasOuter) publishCommand(CommandStatus.FAILED, command, error)
                throw error
            },
        )
    }
}
