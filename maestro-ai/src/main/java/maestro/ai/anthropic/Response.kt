import kotlinx.serialization.Serializable
import maestro.ai.anthropic.Content

@Serializable
data class Response(
    val content: List<Content>,
)
