package maestro.test

import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo
import com.github.tomakehurst.wiremock.junit5.WireMockTest
import com.google.common.truth.Truth.assertThat
import maestro.js.JsEngine
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@WireMockTest
class JsEngineTest {

    lateinit var engine: JsEngine

    @BeforeEach
    fun setUp() {
        engine = JsEngine()
        engine.init()
    }

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
        assertThat(result).isEqualTo("GET Endpoint")
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
        assertThat(result).isEqualTo("GET Endpoint with auth")
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
        assertThat(result).isEqualTo("POST endpoint")
    }

}