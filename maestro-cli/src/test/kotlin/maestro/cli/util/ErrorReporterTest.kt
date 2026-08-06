package maestro.cli.util

import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import picocli.CommandLine
import picocli.CommandLine.Command
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension

@ExtendWith(SystemStubsExtension::class)
class ErrorReporterTest {

    @SystemStub
    private val environmentVariables = EnvironmentVariables()

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
    fun `no error report is sent when MAESTRO_CLI_NO_ANALYTICS is set`() {
        environmentVariables.set("MAESTRO_CLI_NO_ANALYTICS", "1")
        environmentVariables.set("MAESTRO_API_URL", server.url("/").toString().trimEnd('/'))

        ErrorReporter.report(RuntimeException("boom"), parseResult())

        assertThat(server.requestCount).isEqualTo(0)
    }

    @Command(name = "dummy")
    private class DummyCommand

    private fun parseResult(): CommandLine.ParseResult =
        CommandLine(DummyCommand()).parseArgs()
}
