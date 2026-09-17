package maestro.cli.mcp.tools

import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension

/**
 * `cheat_sheet` is the one MCP tool every shipped CLI calls against a URL it cannot change, so the
 * body it receives is the only part of the contract that can still be fixed. The two fixtures here
 * are real captures of that endpoint: `v0-pre-schema` is what the backend served before
 * mobile-dev-inc/copilot#2786, `v2-with-schema` is what it serves after (the same prose, minus the
 * hand-written pressKey list, plus ~19KB of command surface derived from the parser).
 *
 * The tool itself does no parsing -- it hands the body straight to the model -- so what these
 * assert is that neither shape breaks it: not the size increase, not the appended JSON.
 */
@ExtendWith(SystemStubsExtension::class)
class CheatSheetToolTest {

    @SystemStub
    private val environmentVariables = EnvironmentVariables()

    private lateinit var server: MockWebServer

    private fun fixture(name: String): String =
        javaClass.classLoader.getResourceAsStream("cheatsheet/$name")!!
            .bufferedReader().use { it.readText() }

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        environmentVariables.set("MAESTRO_API_URL", server.url("/").toString().trimEnd('/'))
        environmentVariables.set("MAESTRO_CLOUD_API_URL", null)
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun callTool(): io.modelcontextprotocol.kotlin.sdk.types.CallToolResult = runBlocking {
        CheatSheetTool.create().handler(
            mockk<ClientConnection>(relaxed = true),
            CallToolRequest(CallToolRequestParams(name = "cheat_sheet", arguments = buildJsonObject { })),
        )
    }

    private fun enqueue(body: String) {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/plain; charset=utf-8")
                .setBody(body)
        )
    }

    private fun textOf(result: io.modelcontextprotocol.kotlin.sdk.types.CallToolResult): String =
        (result.content.single() as TextContent).text!!

    @Test
    fun `passes the pre-schema body through unchanged`() {
        val body = fixture("v0-pre-schema.txt")
        enqueue(body)

        val result = callTool()

        assertThat(result.isError).isNotEqualTo(true)
        assertThat(textOf(result)).isEqualTo(body)
    }

    @Test
    fun `passes the schema-augmented body through unchanged`() {
        val body = fixture("v2-with-schema.txt")
        enqueue(body)

        val result = callTool()

        assertThat(result.isError).isNotEqualTo(true)
        assertThat(textOf(result)).isEqualTo(body)
    }

    /**
     * The point of the pair: the body roughly doubled (18632 -> 38004 bytes) and gained a JSON
     * block. Whatever the tool did with the old shape it must still do with the new one.
     */
    @Test
    fun `handles both shapes identically despite the size increase`() {
        val v0 = fixture("v0-pre-schema.txt")
        val v2 = fixture("v2-with-schema.txt")

        enqueue(v0)
        val first = callTool()
        enqueue(v2)
        val second = callTool()

        assertThat(textOf(first)).isEqualTo(v0)
        assertThat(textOf(second)).isEqualTo(v2)
        assertThat(second.isError).isEqualTo(first.isError)
        assertThat(textOf(second).length).isGreaterThan(textOf(first).length)
    }

    /** The keys deleted from the prose in #2786 must still reach the model, via the surface. */
    @Test
    fun `the schema-augmented body still carries the keys the prose used to list`() {
        enqueue(fixture("v2-with-schema.txt"))

        val text = textOf(callTool())

        listOf("Remote Dpad Up", "Remote Media Play Pause", "Volume Down", "Backspace")
            .forEach { assertThat(text).contains(it) }
    }

    @Test
    fun `reports an error result when the endpoint fails`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("boom"))

        val result = callTool()

        assertThat(result.isError).isTrue()
        assertThat(textOf(result)).contains("500")
    }

    @Test
    fun `requests the cheat sheet path from the configured host`() {
        enqueue(fixture("v0-pre-schema.txt"))

        callTool()

        val recorded = server.takeRequest()
        assertThat(recorded.method).isEqualTo("GET")
        assertThat(recorded.path).isEqualTo("/v2/bot/maestro-cheat-sheet")
    }
}
