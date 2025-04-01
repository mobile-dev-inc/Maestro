package maestro.ai

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.Closeable

data class CompletionData(
    val prompt: String,
    val model: String,
    val temperature: Float,
    val maxTokens: Int,
    val images: List<String>,
    val response: String,
)

abstract class AI(
    val defaultModel: String,
    protected val httpClient: HttpClient,
) : Closeable {

    /**
     * Chat completion with the AI model.
     *
     * Caveats:
     *  - `jsonSchema` is only supported by OpenAI ("Structured Outputs" feature)
     */
    abstract suspend fun chatCompletion(
        prompt: String,
        images: List<ByteArray> = listOf(),
        temperature: Float? = null,
        model: String? = null,
        maxTokens: Int? = null,
        imageDetail: String? = null,
        jsonSchema: JsonObject? = null,
    ): CompletionData

    companion object {
        const val AI_KEY_ENV_VAR = "MAESTRO_CLI_AI_KEY"
        const val AI_MODEL_ENV_VAR = "MAESTRO_CLI_AI_MODEL"

        val defaultHttpClient = HttpClient {
            install(ContentNegotiation) {
                Json {
                    ignoreUnknownKeys = true
                }
            }

            install(HttpTimeout) {
                connectTimeoutMillis = 10000
                socketTimeoutMillis = 60000
                requestTimeoutMillis = 60000
            }
        }
    }
}
