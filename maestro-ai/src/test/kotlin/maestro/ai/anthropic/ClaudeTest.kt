package maestro.ai.anthropic

import Response
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ClaudeTest {

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
        val client = Claude(
            apiKey = "test-key",
            httpClient = mockHttpClient(responseBody = SUCCESSFUL_RESPONSE),
        )

        val result = client.chatCompletion(prompt = "Say hello")

        assertThat(result.response).isEqualTo("Hello from Claude")
        assertThat(result.model).isEqualTo("claude-3-5-sonnet-20240620")
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

        val client = Claude(apiKey = "test-key", httpClient = mockClient)

        client.chatCompletion(
            prompt = "test",
            model = "claude-3-opus-20240229",
            maxTokens = 4096,
        )

        assertThat(capturedBody).contains("\"model\":\"claude-3-opus-20240229\"")
        assertThat(capturedBody).contains("\"max_tokens\":4096")
    }

    @Test
    fun `chatCompletion sends correct request URL, headers, and body`() = runBlocking {
        var capturedUrl: String? = null
        var capturedApiKeyHeader: String? = null
        var capturedVersionHeader: String? = null
        var capturedBody: String? = null

        val mockClient = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    capturedUrl = request.url.toString()
                    capturedApiKeyHeader = request.headers["x-api-key"]
                    capturedVersionHeader = request.headers["anthropic-version"]
                    capturedBody = (request.body as io.ktor.http.content.TextContent).text
                    respond(
                        content = SUCCESSFUL_RESPONSE,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                    )
                }
            }
        }

        val client = Claude(apiKey = "sk-ant-test-123", httpClient = mockClient)
        client.chatCompletion(prompt = "test prompt")

        assertThat(capturedUrl).isEqualTo("https://api.anthropic.com/v1/messages")
        assertThat(capturedApiKeyHeader).isEqualTo("sk-ant-test-123")
        assertThat(capturedVersionHeader).isEqualTo("2023-06-01")
        assertThat(capturedBody).contains("\"model\":\"claude-3-5-sonnet-20240620\"")
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

        val client = Claude(apiKey = "test-key", httpClient = mockClient)
        val imageBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG magic bytes

        val result = client.chatCompletion(prompt = "describe image", images = listOf(imageBytes))

        assertThat(capturedBody).contains("\"type\":\"image\"")
        assertThat(capturedBody).contains("\"media_type\":\"image/png\"")
        assertThat(capturedBody).contains("\"type\":\"base64\"")
        assertThat(result.images).hasSize(1)
    }

    @Test
    fun `chatCompletion throws on HTTP error`() = runBlocking {
        val client = Claude(
            apiKey = "test-key",
            httpClient = mockHttpClient(
                status = HttpStatusCode.Unauthorized,
                responseBody = """{"error": {"message": "Invalid API key"}}""",
            ),
        )

        val exception = assertThrows<Exception> {
            client.chatCompletion(prompt = "test")
        }
        assertThat(exception.message).contains("Failed to complete request to Anthropic")
        assertThat(exception.message).contains("401")
    }

    @Test
    fun `chatCompletion throws on malformed response`() = runBlocking {
        val client = Claude(
            apiKey = "test-key",
            httpClient = mockHttpClient(responseBody = "not json at all"),
        )

        assertThrows<SerializationException> {
            client.chatCompletion(prompt = "test")
        }
    }

    @Test
    fun `chatCompletion throws on empty content`() = runBlocking {
        val emptyContentResponse = """
            {
                "content": []
            }
        """.trimIndent()

        val client = Claude(
            apiKey = "test-key",
            httpClient = mockHttpClient(responseBody = emptyContentResponse),
        )

        assertThrows<NoSuchElementException> {
            client.chatCompletion(prompt = "test")
        }
    }

    @Test
    fun `chatCompletion throws on null text in content`() = runBlocking {
        val nullTextResponse = """
            {
                "content": [
                    {
                        "type": "image"
                    }
                ]
            }
        """.trimIndent()

        val client = Claude(
            apiKey = "test-key",
            httpClient = mockHttpClient(responseBody = nullTextResponse),
        )

        assertThrows<NullPointerException> {
            client.chatCompletion(prompt = "test")
        }
    }

    // -- Serialization tests --

    @Test
    fun `Request serializes with correct JSON field names`() {
        val request = Request(
            model = "claude-3-5-sonnet-20240620",
            maxTokens = 1024,
            messages = listOf(
                Message(
                    role = "user",
                    content = listOf(Content(type = "text", text = "hello")),
                )
            ),
        )

        val serialized = json.encodeToString(request)

        assertThat(serialized).contains("\"max_tokens\":1024")
        assertThat(serialized).doesNotContain("maxTokens")
        assertThat(serialized).contains("\"model\":\"claude-3-5-sonnet-20240620\"")
    }

    @Test
    fun `Response deserializes from real API format`() {
        val responseJson = """
            {
                "content": [
                    {
                        "type": "text",
                        "text": "The answer is 42"
                    }
                ]
            }
        """.trimIndent()

        val response = json.decodeFromString<Response>(responseJson)

        assertThat(response.content).hasSize(1)
        assertThat(response.content[0].type).isEqualTo("text")
        assertThat(response.content[0].text).isEqualTo("The answer is 42")
    }

    @Test
    fun `Response deserializes with unknown fields`() {
        val responseJson = """
            {
                "id": "msg_123",
                "type": "message",
                "role": "assistant",
                "content": [
                    {
                        "type": "text",
                        "text": "ok"
                    }
                ],
                "stop_reason": "end_turn",
                "usage": { "input_tokens": 10, "output_tokens": 5 }
            }
        """.trimIndent()

        val response = json.decodeFromString<Response>(responseJson)
        assertThat(response.content[0].text).isEqualTo("ok")
    }

    @Test
    fun `Content serializes image source correctly`() {
        val content = Content(
            type = "image",
            source = ContentSource(
                type = "base64",
                mediaType = "image/png",
                data = "iVBORw0KGgo=",
            ),
        )

        val serialized = json.encodeToString(content)

        assertThat(serialized).contains("\"media_type\":\"image/png\"")
        assertThat(serialized).doesNotContain("mediaType")
        assertThat(serialized).contains("\"type\":\"base64\"")
    }

    companion object {
        private val SUCCESSFUL_RESPONSE = """
            {
                "content": [
                    {
                        "type": "text",
                        "text": "Hello from Claude"
                    }
                ]
            }
        """.trimIndent()
    }
}
