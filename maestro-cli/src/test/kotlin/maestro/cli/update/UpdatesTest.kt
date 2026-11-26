package maestro.cli.update

import com.google.common.truth.Truth.assertThat
import maestro.cli.api.CliVersion
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class UpdatesTest {

    private val server = MockWebServer()

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses the version from the latest release redirect`() {
        val location = "https://github.com/mobile-dev-inc/maestro/releases/tag/cli-2.12.1"

        assertThat(Updates.parseReleaseLocation(location)).isEqualTo(CliVersion(2, 12, 1))
    }

    @Test
    fun `returns null when the redirect does not point at a CLI release`() {
        assertThat(Updates.parseReleaseLocation(null)).isNull()
        assertThat(Updates.parseReleaseLocation("https://github.com/mobile-dev-inc/maestro/releases")).isNull()
        assertThat(Updates.parseReleaseLocation("https://github.com/mobile-dev-inc/maestro/releases/tag/v2.12.1")).isNull()
        assertThat(Updates.parseReleaseLocation("https://github.com/mobile-dev-inc/maestro/releases/tag/cli-2.12")).isNull()
    }

    @Test
    fun `reads the latest version from the redirect without following it`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", server.url("/releases/tag/cli-9.8.7").toString())
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val version = Updates.fetchLatestReleaseVersion(server.url("/releases/latest").toString())

        assertThat(version).isEqualTo(CliVersion(9, 8, 7))
        assertThat(server.requestCount).isEqualTo(1)
        assertThat(server.takeRequest().method).isEqualTo("HEAD")
    }

    @Test
    fun `returns null when the latest release page does not redirect`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val version = Updates.fetchLatestReleaseVersion(server.url("/releases/latest").toString())

        assertThat(version).isNull()
    }

    @Test
    fun `update check is disabled by MAESTRO_DISABLE_UPDATE_CHECK`() {
        assertThat(Updates.isDisabled(disableUpdateCheck = "true", ciProvider = null)).isTrue()
    }

    @Test
    fun `update check is disabled on CI`() {
        assertThat(Updates.isDisabled(disableUpdateCheck = null, ciProvider = "github")).isTrue()
    }

    @Test
    fun `update check runs outside CI when not disabled`() {
        assertThat(Updates.isDisabled(disableUpdateCheck = null, ciProvider = null)).isFalse()
        assertThat(Updates.isDisabled(disableUpdateCheck = "false", ciProvider = null)).isFalse()
    }
}
