package maestro.ai.cloud

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import maestro.ai.openai.OpenAI
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger(OpenAI::class.java)

@Serializable
data class ExtractTextWithAiRequest(
    val query: String,
    val screen: ByteArray,
)

@Serializable
data class ExtractTextWithAiResponse(
    val text: String,
)

class ApiClient {
    private val baseUrl by lazy {
        System.getenv("MAESTRO_CLOUD_API_URL") ?: "https://api.copilot.mobile.dev"
    }

    private val json = Json { ignoreUnknownKeys = true }

    val httpClient = HttpClient {
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

    suspend fun extractTextWithAi(
        apiKey: String,
        query: String,
        screen: ByteArray,
    ): ExtractTextWithAiResponse {
        val url = "$baseUrl/v2/extract-text"

        println(url)

        val response = try {
            val httpResponse = httpClient.post(url) {
                headers {
                    append(HttpHeaders.Authorization, "Bearer $apiKey")
                    append(HttpHeaders.ContentType, ContentType.Application.Json.toString()) // Explicitly set JSON content type
                }
                setBody(json.encodeToString(ExtractTextWithAiRequest(query, screen)))
            }

            val body = httpResponse.bodyAsText()
            if (!httpResponse.status.isSuccess()) {
                logger.error("Failed to complete request to OpenAI: URL: $url ${httpResponse.status}, $body")
                throw Exception("Failed to complete request to OpenAI URL: $url: ${httpResponse.status}, $body")
            }

            json.decodeFromString<ExtractTextWithAiResponse>(body)
        } catch (e: SerializationException) {
            logger.error("Failed to parse response from OpenAI", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to complete request to OpenAI", e)
            throw e
        }

        return response
    }

}