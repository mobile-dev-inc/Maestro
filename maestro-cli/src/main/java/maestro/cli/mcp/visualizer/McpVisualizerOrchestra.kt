package maestro.cli.mcp.visualizer

import maestro.Maestro
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object McpVisualizerOrchestra {

    // Process-scoped so commandIds stay monotonic across runs; the frontend sorts by id
    // and the new run's commands land after older ones. An AtomicInteger retains nothing.
    private val nextCommandId = AtomicInteger()

    fun create(maestro: Maestro): Orchestra {
        // Identity-keyed: the same MaestroCommand reference flows from runFlow's input
        // list through to onCommandStart/Complete, so onFlowStart's seeding and runtime
        // status updates resolve to the same commandId. Scoped to one create() call so
        // command instances (and their retained YAML sources) can be GC'd once the
        // orchestra is done.
        val commandIds = IdentityHashMap<MaestroCommand, Int>()
        fun MaestroCommand.id(): String =
            commandIds.computeIfAbsent(this) { nextCommandId.incrementAndGet() }.toString()

        // Insertion-ordered snapshot of the current flow. Cleared on each onFlowStart so
        // multi-flow runs (test plans, directories) each get a fresh scope. The frontend
        // accumulates rows across snapshots because commandIds are globally unique.
        val commands = LinkedHashMap<String, VisualizerEvent.CommandEntry>()

        // Orchestra fires onCommandStart/Complete for nested subflow commands too. We
        // suppress those because the outer `runFlow:` command's yaml already inlines its
        // nested commands — surfacing each sub-step would clutter the log without adding
        // information. Track depth via a stack: top frame = isOuter for the most recently
        // started command; only outer-most frames touch `commands`.
        val depth = ArrayDeque<Boolean>()

        fun publishSnapshot() {
            McpVisualizerEvents.publish(VisualizerEvent.FlowState(commands.values.toList()))
        }

        fun updateStatus(command: MaestroCommand, status: CommandStatus, errorMessage: String? = null) {
            val existing = commands[command.id()] ?: return
            commands[command.id()] = existing.copy(status = status, errorMessage = errorMessage)
            publishSnapshot()
        }

        return Orchestra(
            maestro = maestro,
            onFlowStart = { flowCommands ->
                depth.clear()
                commands.clear()
                flowCommands.forEach { command ->
                    val yaml = command.sourceInfo?.let { it.source.substring(it.startOffset, it.endOffset) }
                        ?: return@forEach
                    val id = command.id()
                    commands[id] = VisualizerEvent.CommandEntry(
                        commandId = id,
                        yaml = yaml,
                        status = CommandStatus.PENDING,
                    )
                }
                publishSnapshot()
            },
            onCommandStart = { _, command ->
                val isOuter = depth.isEmpty()
                depth.addLast(isOuter)
                if (isOuter) updateStatus(command, CommandStatus.STARTED)
            },
            onCommandComplete = { _, command ->
                val wasOuter = depth.removeLast()
                if (wasOuter) updateStatus(command, CommandStatus.COMPLETED)
            },
            onCommandWarned = { _, command ->
                val wasOuter = depth.removeLast()
                if (wasOuter) updateStatus(command, CommandStatus.WARNED)
            },
            onCommandSkipped = { _, command ->
                // onCommandSkipped fires after onCommandStart inside subflows; outside
                // subflows it can fire without a Start (when a top-level command is
                // pre-skipped via `when:`). Pop only if our stack has a frame for it.
                val wasOuter = if (depth.isNotEmpty()) depth.removeLast() else true
                if (wasOuter) updateStatus(command, CommandStatus.SKIPPED)
            },
            onCommandFailed = { _, command, error ->
                val wasOuter = if (depth.isNotEmpty()) depth.removeLast() else true
                if (wasOuter) {
                    updateStatus(command, CommandStatus.FAILED, error.message)
                    // Sweep trailing PENDING rows so they don't sit unresolved after the
                    // flow aborts. Status updates resolve through commands[id()].
                    commands.replaceAll { _, entry ->
                        if (entry.status == CommandStatus.PENDING) entry.copy(status = CommandStatus.SKIPPED)
                        else entry
                    }
                    publishSnapshot()
                }
                throw error
            },
        )
    }
}
