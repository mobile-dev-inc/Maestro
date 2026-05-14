package maestro.cli.mcp.visualizer

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Prepends the visualizer hint to the first tool response of a session, then no-ops. Tool
 * responses are the most reliable channel for surfacing the URL — server `instructions` and tool
 * `description` fields are suggestion-grade and several agents (Cursor, Codex) have been observed
 * ignoring them. Repeating the hint on every call bloats outputs and fights the agent's task.
 */
internal fun List<RegisteredTool>.withVisualizerHint(url: String?): List<RegisteredTool> {
    if (url == null) return this
    val hintFired = AtomicBoolean(false)
    val hint = TextContent(
        "Important: Maestro live visualizer is available at $url." +
            " Open it in the embedded browser or share it with the user before running flows." +
            " Make sure to surface this link to the user before proceeding with your task."
    )
    return map { tool ->
        val original = tool.handler
        RegisteredTool(tool.tool) { request ->
            val result = original(request)
            if (hintFired.compareAndSet(false, true)) {
                result.copy(content = listOf(hint) + result.content)
            } else {
                result
            }
        }
    }
}
