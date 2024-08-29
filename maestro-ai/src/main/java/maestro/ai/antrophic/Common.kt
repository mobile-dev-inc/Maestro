package maestro.ai.antrophic

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Message(
    val role: String,
    val content: List<Content>,
)

@Serializable
data class Content(
    val type: String,
    val text: String? = null,
    val source: ContentSource? = null,
)

@Serializable
data class ContentSource(
    val type: String,
    @SerialName("media_type") val mediaType: String,
    val data: String,
)
