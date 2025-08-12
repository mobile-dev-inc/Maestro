package maestro.cli.api

import com.fasterxml.jackson.annotation.JsonProperty

data class MessageRequest(
    @JsonProperty("sessionId") val sessionId: String,
    val context: List<ContentDetail>,
    val messages: List<ContentDetail>
)

data class ContentDetail(
    val type: String, // "text" or "image_url" for now
    val text: String? = null,
    val image_url: Base64Image? = null
)

data class Base64Image(
    val url: String,
    val detail: String,
)

data class MessageContent(
    val role: String,
    val content: List<ContentDetail> = emptyList(),
    val tool_calls: List<ToolCall>? = null,
    val tool_call_id: String? = null
)

data class ToolCall(
    val id: String,
    val function: ToolFunction
)

data class ToolFunction(
    val name: String,
    val arguments: String?,
)
