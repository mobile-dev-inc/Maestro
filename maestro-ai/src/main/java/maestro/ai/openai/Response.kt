package maestro.ai.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    @SerialName("system_fingerprint") val systemFingerprint: String? = null,
    val usage: Usage? = null,
    val choices: List<Choice>,
)

@Serializable
data class Usage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int,
)

@Serializable
data class Choice(
    val message: Message,
    @SerialName("finish_details") val finishDetails: FinishDetails? = null,
    val index: Int,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class Message(
    val role: String,
    val content: String,
)

@Serializable
data class FinishDetails(
    val type: String,
    val stop: String? = null,
)
