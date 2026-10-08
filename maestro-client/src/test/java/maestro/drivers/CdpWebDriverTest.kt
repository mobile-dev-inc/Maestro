package maestro.drivers

import CdpTarget
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import maestro.MaestroException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.openqa.selenium.InvalidArgumentException
import org.openqa.selenium.TimeoutException
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebDriverException
import java.time.Duration

class CdpWebDriverTest {

    private fun makeDriver() = CdpWebDriver(isStudio = false, isHeadless = false, screenSize = null)

    private fun target(id: String, type: String, url: String) =
        CdpTarget(id = id, title = id, url = url, type = type, webSocketDebuggerUrl = "ws://$id")

    @Test
    fun `selectTarget prefers the window Selenium holds`() {
        val targets = listOf(
            target("popup", "browser_ui", "chrome://omnibox-popup.top-chrome/"),
            target("other-tab", "page", "https://example.com/"),
            target("under-test", "page", "https://www.saucedemo.com/"),
        )

        val selected = makeDriver().selectTarget(targets, windowHandle = "under-test")

        assertThat(selected?.id).isEqualTo("under-test")
    }

    @Test
    fun `selectTarget skips browser and extension targets when the handle is unknown`() {
        // Chrome 151 lists its omnibox WebUI first, and a profile with extensions adds more ahead
        // of the page. Evaluating against any of them reads the wrong DOM.
        val targets = listOf(
            target("worker", "service_worker", "chrome-extension://abc/service_worker.js"),
            target("popup", "browser_ui", "chrome://omnibox-popup.top-chrome/"),
            target("frame", "iframe", "chrome-untrusted://new-tab-page/one-google-bar"),
            target("under-test", "page", "https://www.saucedemo.com/"),
        )

        val selected = makeDriver().selectTarget(targets, windowHandle = null)

        assertThat(selected?.id).isEqualTo("under-test")
    }

    @Test
    fun `selectTarget falls back to an internal page when that is all there is`() {
        val targets = listOf(
            target("popup", "browser_ui", "chrome://omnibox-popup.top-chrome/"),
            target("newtab", "page", "chrome://newtab/"),
        )

        val selected = makeDriver().selectTarget(targets, windowHandle = null)

        assertThat(selected?.id).isEqualTo("newtab")
    }

    @Test
    fun `selectTarget returns null when no page target exists`() {
        val targets = listOf(target("popup", "browser_ui", "chrome://omnibox-popup.top-chrome/"))

        assertThat(makeDriver().selectTarget(targets, windowHandle = null)).isNull()
    }

    @Test
    fun `parseDomAsTreeNodes handles bounds as String`() {
        val dom = mapOf(
            "attributes" to mapOf("text" to "Button", "bounds" to "[10,20][110,60]"),
            "children" to emptyList<Any>(),
        )
        val node = makeDriver().parseDomAsTreeNodes(dom)
        assertThat(node.attributes["bounds"]).isEqualTo("[10,20][110,60]")
    }

    @Test
    fun `parseDomAsTreeNodes handles bounds as LinkedHashMap`() {
        val dom = mapOf(
            "attributes" to mapOf(
                "text" to "Button",
                "bounds" to mapOf("left" to 10, "top" to 20, "right" to 110, "bottom" to 60),
            ),
            "children" to emptyList<Any>(),
        )
        val node = makeDriver().parseDomAsTreeNodes(dom)
        assertThat(node.attributes["bounds"]).isEqualTo("[10,20][110,60]")
    }

    @Test
    fun `parseDomAsTreeNodes uses fallback for unknown bounds type`() {
        val dom = mapOf(
            "attributes" to mapOf("text" to "Button", "bounds" to 42L),
            "children" to emptyList<Any>(),
        )
        val node = makeDriver().parseDomAsTreeNodes(dom)
        assertThat(node.attributes["bounds"]).isEqualTo("[0,0][0,0]")
    }

    private fun driverWhoseNavigationThrows(error: WebDriverException): CdpWebDriver {
        val timeouts = mockk<WebDriver.Timeouts>()
        every { timeouts.pageLoadTimeout } returns Duration.ofSeconds(300)
        every { timeouts.pageLoadTimeout(any()) } returns timeouts

        val options = mockk<WebDriver.Options>()
        every { options.timeouts() } returns timeouts

        val selenium = mockk<WebDriver>()
        every { selenium.manage() } returns options
        every { selenium.get(any()) } throws error

        return makeDriver().apply { seleniumDriver = selenium }
    }

    @Test
    fun `launchApp fails with a MaestroException when the page cannot be opened`() {
        val driver = driverWhoseNavigationThrows(
            WebDriverException("unknown error: net::ERR_CONNECTION_REFUSED\n  (Session info: chrome=147.0)")
        )

        val error = assertThrows<MaestroException.UnableToLaunchApp> {
            driver.launchApp("http://127.0.0.1:1/", emptyMap())
        }

        assertThat(error.message)
            .isEqualTo("Unable to open http://127.0.0.1:1/: unknown error: net::ERR_CONNECTION_REFUSED")
    }

    @Test
    fun `launchApp launches nothing for an app id that is not a URL`() {
        val driver = driverWhoseNavigationThrows(InvalidArgumentException("invalid argument"))

        driver.launchApp("com.example.app", emptyMap())
    }

    @Test
    fun `launchApp continues when the page is still loading at the timeout`() {
        val driver = driverWhoseNavigationThrows(TimeoutException("timeout: Timed out receiving message from renderer"))

        driver.launchApp("https://example.com", emptyMap())
    }
}
