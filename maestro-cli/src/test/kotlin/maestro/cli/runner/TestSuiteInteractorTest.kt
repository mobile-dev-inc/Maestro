package maestro.cli.runner

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import maestro.Maestro
import maestro.cli.report.TestSuiteReporter
import maestro.orchestra.workspace.WorkspaceExecutionPlanner
import maestro.test.drivers.FakeDriver
import maestro.test.drivers.FakeLayoutElement
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

@ExtendWith(SystemStubsExtension::class)
class TestSuiteInteractorTest {

    private val cloud = MockWebServer()

    @SystemStub
    private val environment = EnvironmentVariables()

    @AfterEach
    fun tearDown() {
        cloud.shutdown()
    }

    @Test
    fun `AI commands send the API key when flows run as a suite`(@TempDir dir: Path) {
        cloud.enqueue(MockResponse().setBody("""{"defects": []}"""))
        cloud.start()
        // The AI client reads this once, on its first request in the JVM
        environment.set("MAESTRO_CLOUD_API_URL", cloud.url("/").toString().removeSuffix("/"))

        val flow = dir.resolve("flow.yaml")
        flow.writeText(
            """
            appId: com.example.app
            ---
            - assertWithAI:
                assertion: The screen shows the home page
                optional: false
            """.trimIndent()
        )
        val driver = FakeDriver().apply {
            setLayout(FakeLayoutElement())
            open()
        }

        val summary = Maestro(driver).use { maestro ->
            runBlocking {
                TestSuiteInteractor(
                    maestro = maestro,
                    reporter = TestSuiteReporter.NOOP,
                    apiKey = "test-api-key",
                ).runTestSuite(
                    executionPlan = WorkspaceExecutionPlanner.ExecutionPlan(
                        flowsToRun = listOf(flow),
                        sequence = WorkspaceExecutionPlanner.FlowSequence(emptyList()),
                    ),
                    reportOut = null,
                    env = emptyMap(),
                    debugOutputPath = dir.resolve("debug"),
                )
            }
        }

        val request = cloud.takeRequest(5, TimeUnit.SECONDS)
        assertThat(request?.path).isEqualTo("/v2/find-defects")
        assertThat(request?.getHeader("Authorization")).isEqualTo("Bearer test-api-key")
        assertThat(summary.passed).isTrue()
    }
}
