package maestro.cli.api

import com.google.common.truth.Truth.assertThat
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ApiClientCloudDevicesTest {

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
    fun `reads the device catalog, and falls back to the device list when the backend has no catalog`() {
        val client = ApiClient(server.url("/").toString().trimEnd('/'))

        server.enqueue(MockResponse().setBody(CATALOG))
        val catalog = client.listCloudDevices()
        assertThat(server.takeRequest().path).isEqualTo("/v2/device/catalog")
        assertThat(catalog["ios"]).isEqualTo(
            CloudPlatformDevices(
                default = CloudDefaultDevice("iPhone-11", "iOS-17-5"),
                devices = listOf(
                    CloudDeviceModel(
                        "iPhone-8",
                        CloudDeprecation("2026-11-09"),
                        listOf(CloudDeviceOs("iOS-16-4", CloudDeprecation("2026-11-09"))),
                    ),
                    CloudDeviceModel(
                        "iPhone-11",
                        null,
                        listOf(CloudDeviceOs("iOS-16-4", CloudDeprecation("2026-11-09")), CloudDeviceOs("iOS-17-5", null)),
                    ),
                ),
            )
        )

        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody("""{"ios": {"iPhone-11": ["iOS-17-5"]}}"""))
        val fromList = client.listCloudDevices()
        assertThat(server.takeRequest().path).isEqualTo("/v2/device/catalog")
        assertThat(server.takeRequest().path).isEqualTo("/v2/device/list")
        assertThat(fromList["ios"]).isEqualTo(
            CloudPlatformDevices(null, listOf(CloudDeviceModel("iPhone-11", null, listOf(CloudDeviceOs("iOS-17-5", null)))))
        )
    }

    private companion object {
        val CATALOG = """
            {
              "ios": {
                "default": {"model": "iPhone-11", "os": "iOS-17-5"},
                "devices": [
                  {"model": "iPhone-8", "deprecation": {"removalDate": "2026-11-09"},
                   "osVersions": [{"id": "iOS-16-4", "deprecation": {"removalDate": "2026-11-09"}}]},
                  {"model": "iPhone-11",
                   "osVersions": [{"id": "iOS-16-4", "deprecation": {"removalDate": "2026-11-09"}}, {"id": "iOS-17-5"}]}
                ]
              }
            }
        """.trimIndent()
    }
}
