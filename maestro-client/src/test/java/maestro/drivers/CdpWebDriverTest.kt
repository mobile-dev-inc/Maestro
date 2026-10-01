package maestro.drivers

import CdpTarget
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

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

    @Test
    fun `caller's Chrome arguments come after Maestro's own and the binary is set`() {
        val options = CdpWebDriver.chromeOptions(
            isHeadless = true,
            screenSize = "1920x1080",
            chromeArgs = listOf("--proxy-server=http://127.0.0.1:3128", "--window-size=800,600"),
            chromeBinary = "/opt/chrome/chrome",
        )

        @Suppress("UNCHECKED_CAST")
        val chrome = options.asMap()["goog:chromeOptions"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val args = chrome["args"] as List<String>
        assertThat(args).contains("--headless=new")
        assertThat(args.takeLast(2))
            .containsExactly("--proxy-server=http://127.0.0.1:3128", "--window-size=800,600").inOrder()
        assertThat(args.indexOf("--window-size=1920,1080")).isLessThan(args.indexOf("--window-size=800,600"))
        assertThat(chrome["binary"]).isEqualTo("/opt/chrome/chrome")
    }

    @Test
    fun `with no extra arguments the options are what they were before`() {
        val options = CdpWebDriver.chromeOptions(isHeadless = false, screenSize = null, chromeArgs = emptyList(), chromeBinary = null)

        @Suppress("UNCHECKED_CAST")
        val chrome = options.asMap()["goog:chromeOptions"] as Map<String, Any>
        @Suppress("UNCHECKED_CAST")
        val args = chrome["args"] as List<String>
        assertThat(args).containsExactly(
            "--remote-allow-origins=*", "--disable-search-engine-choice-screen", "--lang=en", "--password-store=basic",
        ).inOrder()
        assertThat(chrome.containsKey("binary")).isFalse()
    }
}
