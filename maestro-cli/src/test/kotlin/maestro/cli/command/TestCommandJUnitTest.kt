package maestro.cli.command

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import maestro.Maestro
import maestro.cli.session.MaestroSessionManager
import maestro.cli.session.MaestroSessionManager.MaestroSession
import maestro.device.Device
import maestro.device.DeviceService
import maestro.device.DeviceSpec
import maestro.device.Platform
import maestro.test.drivers.FakeDriver
import maestro.test.drivers.FakeLayoutElement
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import uk.org.webcompere.systemstubs.environment.EnvironmentVariables
import uk.org.webcompere.systemstubs.jupiter.SystemStub
import uk.org.webcompere.systemstubs.jupiter.SystemStubsExtension
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText
import kotlin.io.path.writeText

@ExtendWith(SystemStubsExtension::class)
class TestCommandJUnitTest {

    @SystemStub
    private val environment = EnvironmentVariables()

    @Test
    fun `AI commands send the API key for a single flow with --format junit`(@TempDir dir: Path) {
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
        val report = dir.resolve("report.xml")
        val driver = FakeDriver().apply {
            setLayout(FakeLayoutElement())
            open()
        }

        MockWebServer().use { cloud ->
            cloud.start()
            cloud.enqueue(MockResponse().setBody("""{"defects": []}"""))
            environment.set("MAESTRO_CLOUD_API_URL", cloud.url("/").toString().removeSuffix("/"))
            environment.set("CI", "true")

            val exitCode = Maestro(driver).use { maestro ->
                withFakeDevice(maestro) {
                    CommandLine(TestCommand()).execute(
                        "--format", "junit",
                        "--api-key", "test-api-key",
                        "--output", report.toString(),
                        "--debug-output", dir.resolve("debug").toString(),
                        "--flatten-debug-output",
                        flow.toString(),
                    )
                }
            }

            assertThat(exitCode).isEqualTo(0)
            assertThat(report.readText()).contains("<testcase ")
            val request = cloud.takeRequest(5, TimeUnit.SECONDS)
            assertThat(request?.path).isEqualTo("/v2/find-defects")
            assertThat(request?.getHeader("Authorization")).isEqualTo("Bearer test-api-key")
        }
    }

    private fun withFakeDevice(maestro: Maestro, runTest: () -> Int): Int {
        val device = Device.Connected(
            instanceId = "fake-android",
            deviceSpec = DeviceSpec.Android.DEFAULT,
            description = "Fake Android",
            platform = Platform.ANDROID,
            deviceType = Device.DeviceType.EMULATOR,
        )
        val session = MaestroSession(maestro, device)

        mockkObject(DeviceService, MaestroSessionManager)
        try {
            every { DeviceService.listConnectedDevices(any(), any(), any()) } returns listOf(device)
            every {
                MaestroSessionManager.newSession<Any>(
                    host = any(),
                    port = any(),
                    driverHostPort = any(),
                    deviceId = any(),
                    teamId = any(),
                    platform = any(),
                    isHeadless = any(),
                    screenSize = any(),
                    reinstallDriver = any(),
                    executionPlan = any(),
                    block = any(),
                )
            } answers {
                lastArg<(MaestroSession) -> Any>()(session)
            }
            return runTest()
        } finally {
            unmockkObject(DeviceService, MaestroSessionManager)
        }
    }
}
