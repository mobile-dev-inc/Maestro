package maestro.cli.api

import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream

/**
 * The token `maestro login` receives is a credential: the CLI stores it but must not write it to
 * the terminal, where it ends up in scrollback, logs and shared output.
 */
class ApiClientExchangeTokenTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `exchanging the code returns the token without printing it`() {
        server.enqueue(MockResponse().setBody(TOKEN))

        val (token, stdout) = captureStdout {
            ApiClient(server.url("/").toString().trimEnd('/')).exchangeToken("auth-code")
        }

        assertThat(token).isEqualTo(TOKEN)
        assertThat(stdout).doesNotContain(TOKEN)
    }

    private fun <T> captureStdout(block: () -> T): Pair<T, String> {
        val original = System.out
        val captured = ByteArrayOutputStream()
        System.setOut(PrintStream(captured, true))
        try {
            return block() to captured.toString()
        } finally {
            System.setOut(original)
        }
    }

    private companion object {
        const val TOKEN = "test-token-not-a-real-credential"
    }
}
