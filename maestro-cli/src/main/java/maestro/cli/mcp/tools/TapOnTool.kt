package maestro.cli.mcp.tools

import kotlinx.serialization.json.JsonElement
import maestro.cli.session.MaestroSessionManager
import kotlinx.serialization.Serializable
import maestro.orchestra.ElementSelector
import maestro.orchestra.TapOnElementCommand
import maestro.cli.mcp.McpCommandTool
import maestro.cli.mcp.JsonSchemaField

@Serializable
data class TapOnParams(
    @JsonSchemaField(description = "The ID of the device to tap on", required = true)
    val device_id: String,
    
    @JsonSchemaField(description = "Text content to match (from 'text' field in inspect_ui output)")
    val text: String? = null,
    
    @JsonSchemaField(description = "Element ID to match (from 'id' field in inspect_ui output)")
    val id: String? = null,
    
    @JsonSchemaField(description = "0-based index if multiple elements match the same criteria")
    val index: Int? = null,
    
    @JsonSchemaField(description = "Whether to use fuzzy/partial text matching (true, default) or exact regex matching (false)")
    val use_fuzzy_matching: Boolean = true,
    
    @JsonSchemaField(description = "Additional filter: only match elements that are enabled (true) or disabled (false)")
    val enabled: Boolean? = null,
    
    @JsonSchemaField(description = "Additional filter: only match elements that are checked (true) or unchecked (false)")
    val checked: Boolean? = null,
    
    @JsonSchemaField(description = "Additional filter: only match elements that are focused")
    val focused: Boolean? = null,
    
    @JsonSchemaField(description = "Additional filter: only match elements that are selected")
    val selected: Boolean? = null
)

/**
 * Tool to tap on a UI element on the connected device.
 * 
 * Uses explicit parameters matching Maestro selector documentation:
 * - Use 'id' parameter for elements with ID from inspect_ui output
 * - Use 'text' parameter for elements with text content
 * - Use 'index' if multiple elements match the same criteria
 * 
 * Example: 
 * - {"device_id": "123", "id": "com.apple.settings.camera"}
 * - {"device_id": "123", "text": "Settings", "index": 0}
 */
class TapOnTool(sessionManager: MaestroSessionManager) : 
    McpCommandTool<TapOnParams, TapOnElementCommand>(sessionManager) {

    override val toolName = "tap_on"
    override val toolDescription = "Tap on a UI element by selector or description."

    override val definition = generateToolDefinition<TapOnParams>(toolName, toolDescription)

    override fun deserializeParams(json: JsonElement) = deserializeJsonParams<TapOnParams>(json)

    override fun createCommand(params: TapOnParams): TapOnElementCommand {
        // Validate that at least one selector is provided
        if (params.text == null && params.id == null) {
            throw IllegalArgumentException("Either 'text' or 'id' parameter must be provided")
        }
        
        val elementSelector = ElementSelector(
            textRegex = if (params.use_fuzzy_matching && params.text != null) ".*${escapeRegex(params.text)}.*" else params.text,
            idRegex = if (params.use_fuzzy_matching && params.id != null) ".*${escapeRegex(params.id)}.*" else params.id,
            index = params.index?.toString(),
            enabled = params.enabled,
            checked = params.checked,
            focused = params.focused,
            selected = params.selected
        )
        
        return TapOnElementCommand(
            selector = elementSelector,
            retryIfNoChange = true,
            waitUntilVisible = true
        )
    }

    override fun getDeviceId(params: TapOnParams): String = params.device_id
    
    /**
     * Escape special regex characters to prevent regex injection issues
     */
    private fun escapeRegex(input: String): String {
        return input.replace(Regex("[()\\[\\]{}+*?^$|.\\\\]")) { "\\${it.value}" }
    }
}
