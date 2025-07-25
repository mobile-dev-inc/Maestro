package maestro.test

import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo
import com.google.common.net.HttpHeaders
import com.google.common.truth.Truth.assertThat
import maestro.js.GraalJsEngine
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class GraalJsEngineTest : JsEngineTest() {

    @BeforeEach
    fun setUp() {
        engine = GraalJsEngine()
    }

    @Test
    fun `Allow redefinitions of variables`() {
        engine.evaluateScript("const foo = null")
        engine.evaluateScript("const foo = null")
    }

    @Test
    fun `You can't share variables between scopes`() {
        engine.evaluateScript("const foo = 'foo'")
        val result = engine.evaluateScript("foo").toString()
        assertThat(result).contains("undefined")
    }

    @Test
    fun `Backslash and newline are supported`() {
        engine.setCopiedText("\\\n")
        engine.putEnv("FOO", "\\\n")

        val result = engine.evaluateScript("maestro.copiedText + FOO").toString()

        assertThat(result).isEqualTo("\\\n\\\n")
    }

    @Test
    fun `parseInt returns an int representation`() {
        val result = engine.evaluateScript("parseInt('1')").toString()
        assertThat(result).isEqualTo("1")
    }
}