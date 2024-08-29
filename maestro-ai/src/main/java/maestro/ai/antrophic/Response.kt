import kotlinx.serialization.Serializable
import maestro.ai.antrophic.Content

@Serializable
data class Response(
    val content: List<Content>,
)
