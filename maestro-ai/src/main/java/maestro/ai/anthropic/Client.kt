package maestro.ai.anthropic

import Response
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.util.encodeBase64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import maestro.ai.AI
import maestro.ai.CompletionData
import org.slf4j.LoggerFactory

private const val API_URL = "https://api.anthropic.com/v1/messages"

private val logger = LoggerFactory.getLogger(Claude::class.java)

class Claude(
    defaultModel: String = "claude-sonnet-4-5-20250929",
    httpClient: HttpClient = defaultHttpClient,
    private val apiKey: String,
    private val defaultTemperature: Float = 0.2f,
    private val defaultMaxTokens: Int = 1024,
) : AI(defaultModel = defaultModel, httpClient = httpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun chatCompletion(
        prompt: String,
        images: List<ByteArray>,
        temperature: Float?,
        model: String?,
        maxTokens: Int?,
        imageDetail: String?,
        jsonSchema: JsonObject?,
    ): CompletionData {
        val imagesBase64 = images.map { it.encodeBase64() }

        // Fallback to Anthropic defaults
        val actualTemperature = temperature ?: defaultTemperature
        val actualModel = model ?: defaultModel
        val actualMaxTokens = maxTokens ?: defaultMaxTokens

        val imageContents = imagesBase64
            .map { imageBase64 ->
                Content(
                    type = "image",
                    source = ContentSource(
                        type = "base64",
                        mediaType = "image/png",
                        data = imageBase64,
                    ),
                )
            }

        val textContent = Content(type = "text", text = prompt)

        val chatCompletionRequest = Request(
            model = actualModel,
            maxTokens = actualMaxTokens,
            messages = listOf(Message("user", imageContents + textContent)),
        )

        val response = try {
            val httpResponse = httpClient.post(API_URL) {
                contentType(ContentType.Application.Json)
                headers["x-api-key"] = apiKey
                headers["anthropic-version"] = "2023-06-01"
                setBody(json.encodeToString(chatCompletionRequest))
            }

            val body = httpResponse.bodyAsText()
            if (!httpResponse.status.isSuccess()) {
                logger.error("Failed to complete request to Anthropic: ${httpResponse.status}, $body")
                throw Exception("Failed to complete request to Anthropic: ${httpResponse.status}, $body")
            }

            if (httpResponse.status != HttpStatusCode.OK) {
                throw IllegalStateException("Call to Anthropic AI failed: $body")
            }

            json.decodeFromString<Response>(httpResponse.bodyAsText())
        } catch (e: SerializationException) {
            logger.error("Failed to parse response from Anthropic", e)
            throw e
        } catch (e: Exception) {
            logger.error("Failed to complete request to Anthropic", e)
            throw e
        }

        return CompletionData(
            prompt = prompt,
            temperature = actualTemperature,
            maxTokens = actualMaxTokens,
            images = imagesBase64,
            model = actualModel,
            response = response.content.first().text!!,
        )
    }

    override fun close() = httpClient.close()
}
