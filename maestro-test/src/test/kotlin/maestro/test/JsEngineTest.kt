package maestro.test

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import com.github.tomakehurst.wiremock.matching.MultipartValuePatternBuilder
import com.google.common.net.HttpHeaders
import com.google.common.truth.Truth.assertThat
import maestro.js.JsEngine
import maestro.js.JsEvaluationException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.file.Files

@WireMockTest
abstract class JsEngineTest {

    lateinit var engine: JsEngine

    @Test
    fun `HTTP - Make GET request`(wiremockInfo: WireMockRuntimeInfo) {
        // Given
        val port = wiremockInfo.httpPort
        stubFor(
            get("/json").willReturn(
                okJson(
                    """
                        {
                            "message": "GET Endpoint"
                        }
                    """.trimIndent()
                )
            )
        )

        val script = """
            var response = http.get('http://localhost:$port/json')
            
            json(response.body).message
        """.trimIndent()

        // When
        val result = engine.evaluateScript(script)

        // Then
        assertThat(result.toString()).isEqualTo("GET Endpoint")
    }

    @Test
    fun `HTTP - Make GET request with headers`(wiremockInfo: WireMockRuntimeInfo) {
        // Given
        val port = wiremockInfo.httpPort
        stubFor(
            get("/json")
                .withHeader("Authorization", equalTo("Bearer Token"))
                .willReturn(
                    okJson(
                        """
                            {
                                "message": "GET Endpoint with auth"
                            }
                        """.trimIndent()
                    )
                )
        )

        val script = """
            var response = http.get('http://localhost:$port/json', {
                headers: {
                    Authorization: 'Bearer Token'
                }
            })
            
            json(response.body).message
        """.trimIndent()

        // When
        val result = engine.evaluateScript(script)

        // Then
        assertThat(result.toString()).isEqualTo("GET Endpoint with auth")
    }

    @Test
    fun `HTTP - Make POST request`(wiremockInfo: WireMockRuntimeInfo) {
        // Given
        val port = wiremockInfo.httpPort
        stubFor(
            post("/json")
                .withRequestBody(
                    equalToJson(
                        """
                            {
                                "payload": "Value"
                            }
                        """.trimIndent()
                    )
                )
                .willReturn(
                    okJson(
                        """
                            {
                                "message": "POST endpoint"
                            }
                        """.trimIndent()
                    )
                )
        )

        val script = """
            var response = http.post('http://localhost:$port/json', {
                body: JSON.stringify(
                    {
                        payload: 'Value'
                    }
                )
            })
            
            json(response.body).message
        """.trimIndent()

        // When
        val result = engine.evaluateScript(script)

        // Then
        assertThat(result.toString()).isEqualTo("POST endpoint")
    }

    @Test
    fun `Allow sharing output object between scripts`() {
        engine.evaluateScript("output.foo = 'foo'")
        val foo = engine.evaluateScript("output.foo")
        assertThat(foo.toString()).isEqualTo("foo")
    }

    @Test
    fun `Undeclared variables are falsy`() {
        val result = engine.evaluateScript("!!foo").toString()
        assertThat(result).isEqualTo("false")
    }

    @Test
    fun `Environment variables are accessible across scopes`() {
        engine.putEnv("FOO", "foo")

        var result = engine.evaluateScript("FOO").toString()
        assertThat(result).isEqualTo("foo")

        engine.enterScope()

        result = engine.evaluateScript("FOO").toString()
        assertThat(result).isEqualTo("foo")
    }

    @Test
    fun `Inline environment variables are accessible across scopes`() {
        var result = engine.evaluateScript("FOO", env = mapOf("FOO" to "foo")).toString()
        assertThat(result).isEqualTo("foo")

        result = engine.evaluateScript("FOO").toString()
        assertThat(result).isEqualTo("foo")

        engine.enterScope()

        result = engine.evaluateScript("FOO").toString()
        assertThat(result).isEqualTo("foo")
    }

    @Test
    fun `HTTP - Make GET request and check response body and headers `(wiremockInfo: WireMockRuntimeInfo) {
        // Given
        val port = wiremockInfo.httpPort
        val body =
            """
                {
                    "message": "GET Endpoint"
                }
            """.trimIndent()

        val testHeader = "testHeader"
        val response = WireMock.aResponse().withStatus(200)
            .withHeader(HttpHeaders.CONTENT_TYPE, "application/json")
            .withHeader(testHeader, "first")
            .withHeader(testHeader, "second")
            .withBody(body)

        stubFor(
            get("/json").willReturn(response)
        )

        val script = """
            var response = http.get('http://localhost:$port/json');
            
            //check body
            var message = json(response.body).message;
            
            // check headers
            var contentType = response.headers['content-type'];
            var testHeader = response.headers['testheader'];
            String(message + String(" ") + contentType + String(" ") + testHeader);
        """.trimIndent()

        // When
        val result = engine.evaluateScript(script)

        // Then
        assertThat(result.toString()).isEqualTo("GET Endpoint application/json first,second")
    }

    @Test
    fun `HTTP - Make POST request with multipart form`(wiremockInfo: WireMockRuntimeInfo) {
        // Given
        val port = wiremockInfo.httpPort
        stubFor(
            post("/json")
                .withMultipartRequestBody(
                    MultipartValuePatternBuilder("uploadType")
                        .withBody(equalTo("import"))
                )
                .withMultipartRequestBody(
                    MultipartValuePatternBuilder("data")
                )
                .willReturn(
                    okJson(
                        """
                            {
                                "message": "POST endpoint"
                            }
                        """.trimIndent()
                    )
                )
        )

        val script = """
            var response = http.post('http://localhost:$port/json', {
                multipartForm: {
                    "uploadType": "import",
                    "data": {
                        "filePath": filePath
                    }
                }
            });

            json(response.body).message
        """.trimIndent()

        // When
        val result = engine.evaluateScript(script)

        // Then
        assertThat(result.toString()).isEqualTo("POST endpoint")
    }

    @Test
    fun `HTTP - Multipart form with multiple files resolves each relative to script`(wiremockInfo: WireMockRuntimeInfo) {
        // Given: Multiple files in different locations
        val tempDir = Files.createTempDirectory("maestro-test")
        try {
            val scriptsDir = tempDir.resolve("scripts").toFile().apply { mkdirs() }
            val mediaDir = tempDir.resolve("media").toFile().apply { mkdirs() }
            val docsDir = tempDir.resolve("docs").toFile().apply { mkdirs() }
            
            val imageFile = mediaDir.resolve("image.txt").apply { writeText("image content") }
            val docFile = docsDir.resolve("doc.txt").apply { writeText("doc content") }
            val scriptFile = scriptsDir.resolve("upload.js")

            val port = wiremockInfo.httpPort
            stubFor(
                post("/upload")
                    .withMultipartRequestBody(
                        MultipartValuePatternBuilder("image")
                            .withBody(equalTo("image content"))
                    )
                    .withMultipartRequestBody(
                        MultipartValuePatternBuilder("document")
                            .withBody(equalTo("doc content"))
                    )
                    .willReturn(okJson("""{"success": true}"""))
            )

            val script = """
                var response = http.post('http://localhost:$port/upload', {
                    multipartForm: {
                        "image": {
                            "filePath": "../media/image.txt",
                            "mediaType": "text/plain"
                        },
                        "document": {
                            "filePath": "../docs/doc.txt",
                            "mediaType": "text/plain"
                        }
                    }
                });
                json(response.body).success
            """.trimIndent()

            // When: Upload multiple files
            val result = engine.evaluateScript(
                script,
                sourceName = scriptFile.absolutePath,
                scriptDir = scriptsDir.absolutePath,
            )

            // Then: All files should be resolved correctly
            assertThat(result.toString()).isEqualTo("true")
        } finally {
            tempDir.toFile().deleteRecursively()
        }
    }

    @Test
    fun `HTTP - the timeout param aborts a request that takes too long`(wiremockInfo: WireMockRuntimeInfo) {
        // Given
        val port = wiremockInfo.httpPort
        // Path-only match so the request's query string still hits the delayed stub.
        stubFor(
            get(urlPathEqualTo("/slow"))
                .willReturn(okJson("""{"message": "too late"}""").withFixedDelay(SLOW_RESPONSE_MS))
        )

        val script = "http.get('http://localhost:$port/slow?access_token=s3cret', { timeout: 250 })"

        // When
        val error = assertThrows<JsEvaluationException> { engine.evaluateScript(script) }

        // Then: the error names both ways of raising the limit
        assertThat(error.detail()).contains("timed out after 250 ms")
        assertThat(error.detail()).contains("MAESTRO_JS_HTTP_TIMEOUT")

        // ...and identifies the request by path without echoing the query string
        assertThat(error.detail()).contains("/slow")
        assertThat(error.detail()).doesNotContain("s3cret")
    }

    @Test
    fun `HTTP - MAESTRO_JS_HTTP_TIMEOUT applies to requests that set no timeout`(wiremockInfo: WireMockRuntimeInfo) {
        // Given
        val port = wiremockInfo.httpPort
        stubFor(get("/slow").willReturn(okJson("""{"message": "too late"}""").withFixedDelay(SLOW_RESPONSE_MS)))
        engine.putEnv("MAESTRO_JS_HTTP_TIMEOUT", "250")

        val script = "http.get('http://localhost:$port/slow')"

        // When
        val error = assertThrows<JsEvaluationException> { engine.evaluateScript(script) }

        // Then
        assertThat(error.detail()).contains("timed out after 250 ms")
    }

    @Test
    fun `HTTP - the timeout param overrides MAESTRO_JS_HTTP_TIMEOUT`(wiremockInfo: WireMockRuntimeInfo) {
        // Given: an env default that the response would breach
        val port = wiremockInfo.httpPort
        stubFor(get("/slow").willReturn(okJson("""{"message": "in time"}""").withFixedDelay(400)))
        engine.putEnv("MAESTRO_JS_HTTP_TIMEOUT", "250")

        val script = """
            var response = http.get('http://localhost:$port/slow', { timeout: 10000 })

            json(response.body).message
        """.trimIndent()

        // When
        val result = engine.evaluateScript(script)

        // Then
        assertThat(result.toString()).isEqualTo("in time")
    }

    @Test
    fun `HTTP - a non-numeric timeout fails with an explanatory error`(wiremockInfo: WireMockRuntimeInfo) {
        // Given
        val port = wiremockInfo.httpPort

        val script = "http.get('http://localhost:$port/json', { timeout: 'soon' })"

        // When
        val error = assertThrows<JsEvaluationException> { engine.evaluateScript(script) }

        // Then
        assertThat(error.detail()).contains("must be a whole number of milliseconds")
    }

    @Test
    fun `HTTP - a timed-out request is catchable in the flow's own script`(wiremockInfo: WireMockRuntimeInfo) {
        // Given
        val port = wiremockInfo.httpPort
        stubFor(get("/slow").willReturn(okJson("""{"message": "too late"}""").withFixedDelay(SLOW_RESPONSE_MS)))

        val script = """
            try {
                http.get('http://localhost:$port/slow', { timeout: 250 })
                'not reached'
            } catch (e) {
                String(e.message)
            }
        """.trimIndent()

        // When
        val result = engine.evaluateScript(script)

        // Then: flows that catch their own HTTP failures still see a message, and it is the new one
        assertThat(result.toString()).contains("timed out after 250 ms")
        assertThat(result.toString()).contains("MAESTRO_JS_HTTP_TIMEOUT")
    }

    /**
     * Graal can carry a host exception's message on either field, depending on how it wraps it.
     */
    private fun JsEvaluationException.detail() =
        listOfNotNull(error.message, error.causeMessage).joinToString(" | ")

    private companion object {
        /** Long enough that a sub-second timeout always fires first, short enough not to slow the suite. */
        const val SLOW_RESPONSE_MS = 1500
    }
}
