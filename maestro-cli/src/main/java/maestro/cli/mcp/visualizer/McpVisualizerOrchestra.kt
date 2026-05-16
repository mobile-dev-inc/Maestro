package maestro.cli.mcp.visualizer

import maestro.Maestro
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import java.util.UUID

internal object McpVisualizerOrchestra {

    fun create(maestro: Maestro): Orchestra {
        var flowId = UUID.randomUUID().toString()
        // Orchestra fires onCommandStart/Complete for nested subflow commands too,
        // each with a sub-index that restarts at 0. If we publish those, their
        // callIds (`flowId:index`) collide with outer-flow rows and the visualizer's
        // upsert reuses the wrong row, making old commands appear "running" again.
        // Track depth via a stack — push on Start, pop on terminal — and publish only
        // for the outer-most level. The outer command's yaml already inlines its
        // nested commands, so users still see what's running.
        val depth = ArrayDeque<Boolean>() // top-of-stack = isOuter for the most recently started command

        fun publishCommand(status: CommandStatus, index: Int, command: MaestroCommand, error: Throwable? = null) {
            McpVisualizerEvents.publish(
                VisualizerEvent.Command(
                    status = status,
                    callId = "$flowId:$index",
                    yaml = command.sourceInfo?.let { it.source.substring(it.startOffset, it.endOffset) },
                    errorMessage = error?.message,
                )
            )
        }

        return Orchestra(
            maestro = maestro,
            onFlowStart = {
                flowId = UUID.randomUUID().toString()
                depth.clear()
            },
            onCommandStart = { index, command ->
                val isOuter = depth.isEmpty()
                depth.addLast(isOuter)
                if (isOuter) publishCommand(CommandStatus.STARTED, index, command)
            },
            onCommandComplete = { index, command ->
                val wasOuter = depth.removeLast()
                if (wasOuter) publishCommand(CommandStatus.COMPLETED, index, command)
            },
            onCommandWarned = { index, command ->
                val wasOuter = depth.removeLast()
                if (wasOuter) publishCommand(CommandStatus.WARNED, index, command)
            },
            onCommandSkipped = { index, command ->
                // onCommandSkipped fires after onCommandStart inside subflows; outside
                // subflows it can fire without a Start (when a top-level command is
                // pre-skipped via `when:`). Pop only if our stack has a frame for it.
                val wasOuter = if (depth.isNotEmpty()) depth.removeLast() else true
                if (wasOuter) publishCommand(CommandStatus.SKIPPED, index, command)
            },
            onCommandFailed = { index, command, error ->
                val wasOuter = if (depth.isNotEmpty()) depth.removeLast() else true
                if (wasOuter) publishCommand(CommandStatus.FAILED, index, command, error)
                throw error
            },
        )
    }
}
