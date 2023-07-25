package maestro.test

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo
import com.google.common.net.HttpHeaders
import com.google.common.truth.Truth.assertThat
import maestro.js.RhinoJsEngine
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mozilla.javascript.RhinoException

class RhinoJsEngineTest : JsEngineTest() {

    @BeforeEach
    fun setUp() {
        engine = RhinoJsEngine()
    }

    @Test
    fun `Redefinitions of variables are not allowed`() {
        engine.evaluateScript("const foo = null")

        assertThrows<RhinoException> {
            engine.evaluateScript("const foo = null")
        }
    }

    @Test
    fun `You can access variables across scopes`() {
        engine.evaluateScript("const foo = 'foo'")
        assertThat(engine.evaluateScript("foo")).isEqualTo("foo")

        engine.enterScope()
        assertThat(engine.evaluateScript("foo")).isEqualTo("foo")
    }

    @Test
    fun `Backslash and newline are not supported`() {
        assertThrows<RhinoException> {
            engine.setCopiedText("\\")
        }

        assertThrows<RhinoException> {
            engine.putEnv("FOO", "\\")
        }

        engine.setCopiedText("\n")
        engine.putEnv("FOO", "\n")

        val result = engine.evaluateScript("maestro.copiedText + FOO").toString()

        assertThat(result).isEqualTo("")
    }

    @Test
    fun `parseInt returns a double representation`() {
        val result = engine.evaluateScript("parseInt('1')").toString()
        assertThat(result).isEqualTo("1.0")
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
            .withBody(body);

        WireMock.stubFor(
            WireMock.get("/json").willReturn(response)
        )

        val script = """
            var response = http.get('http://localhost:$port/json');
            
            //check body
            var message = json(response.body).message;
            
            
            // check headers
            var contentType = response.headers.get("content-type");
            var testHeader = response.headers.get("testHeader");
            String(message + String(" ") + contentType.get(0)) + String(" ") + String(testHeader.get(0)) + String(" ") + String(testHeader.get(1));
        """.trimIndent()

        // When
        val result = engine.evaluateScript(script)

        // Then
        assertThat(result.toString()).isEqualTo("GET Endpoint application/json first second")
    }
}