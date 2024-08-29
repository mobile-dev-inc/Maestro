package maestro.ai.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import maestro.ai.common.Base64Image

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<MessageContent>,
    val temperature: Float,
    @SerialName("max_tokens") val maxTokens: Int,
    @SerialName("response_format") val responseFormat: ResponseFormat?,
    val seed: Int,
)

@Serializable
class ResponseFormat(
    val type: String,
    @SerialName("json_schema") val jsonSchema: JsonObject,
)

@Serializable
data class MessageContent(
    val role: String,
    val content: List<ContentDetail>,
)

@Serializable
data class ContentDetail(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: Base64Image? = null,
)
