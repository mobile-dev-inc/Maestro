package maestro.cli.mcp.visualizer

import maestro.Maestro
import maestro.orchestra.CompositeCommand
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

        // Insertion-ordered snapshot of every command in the current flow — top-level
        // and nested. The frontend chooses what to render (today: depth 0 only). The
        // map is cleared on each onFlowStart so multi-flow runs each get a fresh scope;
        // the frontend accumulates rows across snapshots because commandIds are
        // globally unique.
        val commands = LinkedHashMap<String, VisualizerEvent.CommandEntry>()

        fun publishSnapshot() {
            McpVisualizerEvents.publish(VisualizerEvent.FlowState(commands.values.toList()))
        }

        fun updateStatus(command: MaestroCommand, status: CommandStatus, errorMessage: String? = null) {
            val existing = commands[command.id()] ?: return
            commands[command.id()] = existing.copy(status = status, errorMessage = errorMessage)
            publishSnapshot()
        }

        // Walk the full tree at flow start so every command — top-level and nested —
        // gets a PENDING row up front. Runtime callbacks just flip statuses on the
        // entries seeded here.
        fun seed(command: MaestroCommand, depth: Int) {
            val info = command.sourceInfo
            if (info != null) {
                val id = command.id()
                commands[id] = VisualizerEvent.CommandEntry(
                    commandId = id,
                    yaml = info.source.substring(info.startOffset, info.endOffset),
                    depth = depth,
                    status = CommandStatus.PENDING,
                )
            }
            (command.asCommand() as? CompositeCommand)?.subCommands()?.forEach { child ->
                seed(child, depth + 1)
            }
        }

        return Orchestra(
            maestro = maestro,
            onFlowStart = { flowCommands ->
                commands.clear()
                flowCommands.forEach { seed(it, depth = 0) }
                publishSnapshot()
            },
            onCommandStart = { _, command -> updateStatus(command, CommandStatus.STARTED) },
            onCommandComplete = { _, command -> updateStatus(command, CommandStatus.COMPLETED) },
            onCommandWarned = { _, command -> updateStatus(command, CommandStatus.WARNED) },
            onCommandSkipped = { _, command -> updateStatus(command, CommandStatus.SKIPPED) },
            onCommandFailed = { _, command, error ->
                updateStatus(command, CommandStatus.FAILED, error.message)
                // Sweep trailing PENDING rows so they don't sit unresolved after the
                // flow aborts. Idempotent — repeated failures up the ancestor chain
                // each call this but only the first sweep does work.
                commands.replaceAll { _, entry ->
                    if (entry.status == CommandStatus.PENDING) entry.copy(status = CommandStatus.SKIPPED)
                    else entry
                }
                publishSnapshot()
                throw error
            },
        )
    }
}
