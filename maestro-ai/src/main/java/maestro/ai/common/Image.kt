package maestro.ai.common

import kotlinx.serialization.Serializable

@Serializable
data class Base64Image(
    val url: String,
    val detail: String,
)
