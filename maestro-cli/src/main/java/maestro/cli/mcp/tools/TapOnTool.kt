package maestro.cli.mcp.tools

import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.RegisteredTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import maestro.cli.mcp.MaestroTool
import maestro.cli.mcp.schema.McpToolInput
import maestro.cli.session.MaestroSessionManager
import maestro.orchestra.ElementSelector
import maestro.orchestra.MaestroCommand
import maestro.orchestra.Orchestra
import maestro.orchestra.TapOnElementCommand

// Schema definitions for this tool
@Serializable
data class TapOnInput(
    val deviceId: String,
    val text: String? = null,
    val id: String? = null,
    val index: Int? = null,
    val useFuzzyMatching: Boolean = true,
    val enabled: Boolean? = null,
    val checked: Boolean? = null,
    val focused: Boolean? = null,
    val selected: Boolean? = null
) : McpToolInput

@Serializable
data class TapOnOutput(
    val success: Boolean,
    val deviceId: String,
    val message: String
)

object TapOnTool {
    fun create(sessionManager: MaestroSessionManager): RegisteredTool {
        return MaestroTool.create<TapOnInput, TapOnOutput>(
            name = "tap_on",
            description = "Tap on a UI element by selector or description"
        ) { input ->
            // Validate that at least one selector is provided
            if (input.text == null && input.id == null) {
                throw Exception("Either 'text' or 'id' parameter must be provided")
            }

            val message = sessionManager.newSession(
                host = null,
                port = null,
                driverHostPort = null,
                deviceId = input.deviceId,
                platform = null
            ) { session ->
                // Escape special regex characters to prevent regex injection issues
                fun escapeRegex(input: String): String {
                    return input.replace(Regex("[()\\[\\]{}+*?^$|.\\\\]")) { "\\${it.value}" }
                }

                val elementSelector = ElementSelector(
                    textRegex = if (input.useFuzzyMatching && input.text != null) ".*${escapeRegex(input.text)}.*" else input.text,
                    idRegex = if (input.useFuzzyMatching && input.id != null) ".*${escapeRegex(input.id)}.*" else input.id,
                    index = input.index?.toString(),
                    enabled = input.enabled,
                    checked = input.checked,
                    focused = input.focused,
                    selected = input.selected
                )

                val command = TapOnElementCommand(
                    selector = elementSelector,
                    retryIfNoChange = true,
                    waitUntilVisible = true
                )

                val orchestra = Orchestra(session.maestro)
                runBlocking {
                    orchestra.executeCommands(listOf(MaestroCommand(command = command)))
                }

                "Tap executed successfully"
            }

            TapOnOutput(
                success = true,
                deviceId = input.deviceId,
                message = message
            )
        }
    }
}