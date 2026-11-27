package maestro.ai.openai

import com.google.common.truth.Truth.assertThat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OpenAITest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun mockHttpClient(
        status: HttpStatusCode = HttpStatusCode.OK,
        responseBody: String,
    ): HttpClient {
        return HttpClient(MockEngine) {
            engine {
                addHandler {
                    respond(
                        content = responseBody,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }
    }

    @Test
    fun `chatCompletion returns response on success`() = runBlocking {
        val client = OpenAI(
            apiKey = "test-key",
            httpClient = mockHttpClient(responseBody = SUCCESSFUL_RESPONSE),
        )

        val result = client.chatCompletion(prompt = "Say hello")

        assertThat(result.response).isEqualTo("Hello from OpenAI")
        assertThat(result.model).isEqualTo("gpt-4o")
        assertThat(result.prompt).isEqualTo("Say hello")
        assertThat(result.temperature).isEqualTo(0.2f)
        assertThat(result.maxTokens).isEqualTo(1024)
    }

    @Test
    fun `chatCompletion sends custom parameters in request body`() = runBlocking {
        var capturedBody: String? = null

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedBody = (request.body as io.ktor.http.content.TextContent).text
                    respond(
                        content = SUCCESSFUL_RESPONSE,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val client = OpenAI(apiKey = "test-key", httpClient = mockClient)

        client.chatCompletion(
            prompt = "test",
            model = "gpt-4o-mini",
            temperature = 0.8f,
            maxTokens = 4096,
        )

        assertThat(capturedBody).contains("\"model\":\"gpt-4o-mini\"")
        assertThat(capturedBody).contains("\"temperature\":0.8")
        assertThat(capturedBody).contains("\"max_tokens\":4096")
    }

    @Test
    fun `chatCompletion sends correct request URL, headers, and body`() = runBlocking {
        var capturedUrl: String? = null
        var capturedAuthHeader: String? = null
        var capturedBody: String? = null

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedUrl = request.url.toString()
                    capturedAuthHeader = request.headers["Authorization"]
                    capturedBody = (request.body as io.ktor.http.content.TextContent).text
                    respond(
                        content = SUCCESSFUL_RESPONSE,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val client = OpenAI(apiKey = "sk-test-123", httpClient = mockClient)
        client.chatCompletion(prompt = "test prompt")

        assertThat(capturedUrl).isEqualTo("https://api.openai.com/v1/chat/completions")
        assertThat(capturedAuthHeader).isEqualTo("Bearer sk-test-123")
        assertThat(capturedBody).contains("\"model\":\"gpt-4o\"")
        assertThat(capturedBody).contains("test prompt")
    }

    @Test
    fun `chatCompletion encodes images as base64 in request`() = runBlocking {
        var capturedBody: String? = null

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedBody = (request.body as io.ktor.http.content.TextContent).text
                    respond(
                        content = SUCCESSFUL_RESPONSE,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val client = OpenAI(apiKey = "test-key", httpClient = mockClient)
        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG magic bytes

        val result = client.chatCompletion(prompt = "describe image", images = listOf(imageBytes))

        assertThat(capturedBody).contains("image_url")
        assertThat(capturedBody).contains("data:image/png;base64,")
        assertThat(result.images).hasSize(1)
    }

    @Test
    fun `chatCompletion includes responseFormat when jsonSchema provided`() = runBlocking {
        var capturedBody: String? = null

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedBody = (request.body as io.ktor.http.content.TextContent).text
                    respond(
                        content = SUCCESSFUL_RESPONSE,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val schema = JsonObject(mapOf("type" to JsonPrimitive("object")))
        val client = OpenAI(apiKey = "test-key", httpClient = mockClient)
        client.chatCompletion(prompt = "test", jsonSchema = schema)

        assertThat(capturedBody).contains("\"response_format\"")
        assertThat(capturedBody).contains("\"json_schema\"")
    }

    @Test
    fun `chatCompletion sends null responseFormat when no jsonSchema`() = runBlocking {
        var capturedBody: String? = null

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedBody = (request.body as io.ktor.http.content.TextContent).text
                    respond(
                        content = SUCCESSFUL_RESPONSE,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val client = OpenAI(apiKey = "test-key", httpClient = mockClient)
        client.chatCompletion(prompt = "test")

        assertThat(capturedBody).contains("\"response_format\":null")
        assertThat(capturedBody).doesNotContain("\"json_schema\"")
    }

    @Test
    fun `chatCompletion throws on HTTP error`() = runBlocking {
        val client = OpenAI(
            apiKey = "test-key",
            httpClient = mockHttpClient(
                status = HttpStatusCode.Unauthorized,
                responseBody = """{"error": {"message": "Invalid API key"}}""",
            ),
        )

        val exception = assertThrows<Exception> {
            client.chatCompletion(prompt = "test")
        }
        assertThat(exception.message).contains("Failed to complete request to OpenAI")
        assertThat(exception.message).contains("401")
    }

    @Test
    fun `chatCompletion throws on malformed response`() = runBlocking {
        val client = OpenAI(
            apiKey = "test-key",
            httpClient = mockHttpClient(responseBody = "not json at all"),
        )

        assertThrows<SerializationException> {
            client.chatCompletion(prompt = "test")
        }
    }

    @Test
    fun `chatCompletion throws on empty choices`() = runBlocking {
        val emptyChoicesResponse = """
            {
                "id": "chatcmpl-empty",
                "object": "chat.completion",
                "created": 1700000000,
                "model": "gpt-4o",
                "choices": []
            }
        """.trimIndent()

        val client = OpenAI(
            apiKey = "test-key",
            httpClient = mockHttpClient(responseBody = emptyChoicesResponse),
        )

        assertThrows<NoSuchElementException> {
            client.chatCompletion(prompt = "test")
        }
    }

    @Test
    fun `chatCompletion handles response with no usage field`() = runBlocking {
        val responseJson = """
            {
                "id": "chatcmpl-789",
                "object": "chat.completion",
                "created": 1700000000,
                "model": "gpt-4o",
                "choices": [
                    {
                        "message": { "role": "assistant", "content": "no usage" },
                        "index": 0,
                        "finish_reason": "stop"
                    }
                ]
            }
        """.trimIndent()

        val client = OpenAI(
            apiKey = "test-key",
            httpClient = mockHttpClient(responseBody = responseJson),
        )

        val result = client.chatCompletion(prompt = "test")
        assertThat(result.response).isEqualTo("no usage")
    }

    // -- Serialization tests --

    @Test
    fun `ChatCompletionRequest serializes with correct JSON field names`() {
        val request = ChatCompletionRequest(
            model = "gpt-4o",
            messages = listOf(
                MessageContent(
                    role = "user",
                    content = listOf(ContentDetail(type = "text", text = "hello")),
                )
            ),
            temperature = 0.5f,
            maxTokens = 100,
            responseFormat = null,
            seed = 42,
        )

        val serialized = json.encodeToString(request)

        assertThat(serialized).contains("\"max_tokens\":100")
        assertThat(serialized).doesNotContain("maxTokens")
        assertThat(serialized).contains("\"model\":\"gpt-4o\"")
    }

    @Test
    fun `ChatCompletionResponse deserializes from real API format`() {
        val responseJson = """
            {
                "id": "chatcmpl-abc",
                "object": "chat.completion",
                "created": 1700000000,
                "model": "gpt-4o-2024-05-13",
                "system_fingerprint": "fp_abc123",
                "usage": {
                    "prompt_tokens": 50,
                    "completion_tokens": 20,
                    "total_tokens": 70
                },
                "choices": [
                    {
                        "message": { "role": "assistant", "content": "test response" },
                        "index": 0,
                        "finish_reason": "stop"
                    }
                ]
            }
        """.trimIndent()

        val response = json.decodeFromString<ChatCompletionResponse>(responseJson)

        assertThat(response.id).isEqualTo("chatcmpl-abc")
        assertThat(response.model).isEqualTo("gpt-4o-2024-05-13")
        assertThat(response.systemFingerprint).isEqualTo("fp_abc123")
        assertThat(response.usage?.promptTokens).isEqualTo(50)
        assertThat(response.usage?.completionTokens).isEqualTo(20)
        assertThat(response.usage?.totalTokens).isEqualTo(70)
        assertThat(response.choices).hasSize(1)
        assertThat(response.choices[0].message.content).isEqualTo("test response")
        assertThat(response.choices[0].finishReason).isEqualTo("stop")
    }

    @Test
    fun `ChatCompletionResponse deserializes with unknown fields`() {
        val responseJson = """
            {
                "id": "chatcmpl-abc",
                "object": "chat.completion",
                "created": 1700000000,
                "model": "gpt-4o",
                "choices": [
                    {
                        "message": { "role": "assistant", "content": "ok" },
                        "index": 0,
                        "finish_reason": "stop",
                        "some_new_field": true
                    }
                ],
                "new_top_level_field": "should be ignored"
            }
        """.trimIndent()

        val response = json.decodeFromString<ChatCompletionResponse>(responseJson)
        assertThat(response.choices[0].message.content).isEqualTo("ok")
    }

    companion object {
        private val SUCCESSFUL_RESPONSE = """
            {
                "id": "chatcmpl-test",
                "object": "chat.completion",
                "created": 1700000000,
                "model": "gpt-4o",
                "choices": [
                    {
                        "message": { "role": "assistant", "content": "Hello from OpenAI" },
                        "index": 0,
                        "finish_reason": "stop"
                    }
                ]
            }
        """.trimIndent()
    }
}
