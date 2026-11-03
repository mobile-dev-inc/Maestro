package maestro.cli

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import maestro.cli.api.ApiClient
import maestro.cli.api.CloudPlatformDevices
import maestro.cli.command.ListCloudDevicesCommand
import maestro.cli.mcp.tools.ListCloudDevicesTool
import maestro.device.Platform
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** From the HTTP response to what `list-cloud-devices` prints and what MCP `list_cloud_devices` returns. */
class CloudDevicesTest {

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
    fun `catalog shows defaults and deprecations`() {
        server.enqueue(MockResponse().setBody(CATALOG))
        val devices = ApiClient(server.url("/").toString().trimEnd('/')).listCloudDevices()

        assertThat(server.takeRequest().path).isEqualTo("/v2/device/catalog")
        assertThat(cliLines(devices)).containsExactly(
            "iOS   default: iPhone-11 on iOS-17-5",
            "  iPhone-8    iOS-16-4*   deprecated, removed 2026-11-11",
            "  iPhone-11   iOS-16-4*, iOS-17-5",
            "",
            "* Deprecated, removed from Maestro Cloud on 2026-11-11: iOS-16-4",
        ).inOrder()
        assertThat(ListCloudDevicesTool.toJson(devices)).isEqualTo(json("""
            {"devices": [
              {"platform": "ios", "model": "iPhone-8", "supported_os": ["iOS-16-4"],
               "deprecated_os": {"iOS-16-4": "2026-11-11"}, "removal_date": "2026-11-11"},
              {"platform": "ios", "model": "iPhone-11", "supported_os": ["iOS-16-4", "iOS-17-5"],
               "deprecated_os": {"iOS-16-4": "2026-11-11"}}
             ],
             "defaults": {"ios": {"model": "iPhone-11", "os": "iOS-17-5"}}}
        """))
    }

    @Test
    fun `a catalog that errors or is not JSON falls back to the plain list, shown as before`() {
        // An old backend's error, and a proxy page served with 200.
        for (failedCatalog in listOf(MockResponse().setResponseCode(503), MockResponse().setBody("<html>proxy</html>"))) {
            server.enqueue(failedCatalog)
            server.enqueue(MockResponse().setBody("""{"ios": {"iPhone-11": ["iOS-17-5"]}}"""))
            val devices = ApiClient(server.url("/").toString().trimEnd('/')).listCloudDevices()

            assertThat(server.takeRequest().path).isEqualTo("/v2/device/catalog")
            assertThat(server.takeRequest().path).isEqualTo("/v2/device/list")
            assertThat(cliLines(devices)).containsExactly("iOS", "  iPhone-11   iOS-17-5").inOrder()
            assertThat(ListCloudDevicesTool.toJson(devices)).isEqualTo(json("""
                {"devices": [{"platform": "ios", "model": "iPhone-11", "supported_os": ["iOS-17-5"]}]}
            """))
        }
    }

    private fun cliLines(devices: Map<String, CloudPlatformDevices>) =
        ListCloudDevicesCommand().renderDevices(devices, listOf(Platform.IOS))
            .map { it.replace(Regex("\u001B\\[[\\d;]*[^\\d;]"), "").trimEnd() }

    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private companion object {
        val CATALOG = """
            {"ios": {
              "default": {"model": "iPhone-11", "os": "iOS-17-5"},
              "devices": [
                {"model": "iPhone-8", "removalDate": "2026-11-11",
                 "osVersions": [{"id": "iOS-16-4", "removalDate": "2026-11-11"}]},
                {"model": "iPhone-11",
                 "osVersions": [{"id": "iOS-16-4", "removalDate": "2026-11-11"}, {"id": "iOS-17-5"}]}
              ]
            }}
        """.trimIndent()
    }
}
