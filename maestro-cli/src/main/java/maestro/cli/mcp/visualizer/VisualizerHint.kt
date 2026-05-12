package maestro.cli.mcp.visualizer

import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import io.modelcontextprotocol.kotlin.sdk.types.TextContent

/**
 * Prepends a one-line visualizer hint to every tool response. Tool responses are the most reliable
 * channel for surfacing the URL — server `instructions` and tool `description` fields are
 * suggestion-grade and several agents (Cursor, Codex) have been observed ignoring them.
 */
internal fun RegisteredTool.withVisualizerHint(url: String?): RegisteredTool {
    if (url == null) return this
    val hint = TextContent(
        "Important: Maestro live visualizer is available at $url." +
            " Open it in the embedded browser or share it with the user before running flows." +
            " Make sure to surface this link to the user before proceeding with your task."
    )
    val original = this.handler
    return RegisteredTool(this.tool) { request ->
        val result = original(request)
        result.copy(content = listOf(hint) + result.content)
    }
}
