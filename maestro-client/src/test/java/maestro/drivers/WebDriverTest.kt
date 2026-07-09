package maestro.drivers

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import maestro.web.selenium.SeleniumFactory
import okio.blackholeSink
import org.junit.jupiter.api.Test
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.StaleElementReferenceException
import org.openqa.selenium.WebElement
import org.openqa.selenium.devtools.DevTools
import org.openqa.selenium.devtools.DevToolsException
import org.openqa.selenium.devtools.HasDevTools
import java.lang.reflect.InvocationTargetException
import java.util.ServiceConfigurationError
import org.openqa.selenium.WebDriver as SeleniumWebDriver

class WebDriverTest {

    private fun makeDriver(): WebDriver {
        val seleniumDriver = mockk<SeleniumWebDriver>(relaxed = true)
        return WebDriver(
            isStudio = false,
            isHeadless = true,
            screenSize = null,
            seleniumFactory = object : SeleniumFactory {
                override fun create(): SeleniumWebDriver = seleniumDriver
            },
        )
    }

    @Test
    fun `open() does not propagate ServiceConfigurationError from DevTools setup`() {
        val seleniumDriver = mockk<SeleniumWebDriver>(
            relaxed = true,
            moreInterfaces = arrayOf(HasDevTools::class),
        )
        every { (seleniumDriver as HasDevTools).devTools } throws
            ServiceConfigurationError(
                "org.openqa.selenium.devtools.CdpInfo: " +
                    "Provider org.openqa.selenium.devtools.v145.v145CdpInfo not found"
            )

        val factory = object : SeleniumFactory {
            override fun create(): SeleniumWebDriver = seleniumDriver
        }

        val driver = WebDriver(
            isStudio = false,
            isHeadless = true,
            screenSize = null,
            seleniumFactory = factory,
        )

        driver.open()
    }

    @Test
    fun `fetchCrossOriginIframeContent returns null when switchTo frame throws StaleElementReferenceException`() {
        val seleniumDriver = mockk<SeleniumWebDriver>(
            relaxed = true,
            moreInterfaces = arrayOf(JavascriptExecutor::class, HasDevTools::class),
        )
        val iframeElement = mockk<WebElement>(relaxed = true)
        val targetLocator = mockk<SeleniumWebDriver.TargetLocator>(relaxed = true)

        val iframeSrc = "https://example.com/iframe"
        val executor = seleniumDriver as JavascriptExecutor

        every {
            executor.executeScript(match<String> { it.contains("querySelectorAll('iframe')") }, iframeSrc)
        } returns iframeElement

        every {
            executor.executeScript(match<String> { it.contains("getIframeViewportParams") }, iframeSrc)
        } returns """{"viewportX":0,"viewportY":0,"viewportWidth":100,"viewportHeight":100}"""

        every { seleniumDriver.switchTo() } returns targetLocator
        every { targetLocator.frame(iframeElement) } throws
            StaleElementReferenceException("stale element not found")

        val factory = object : SeleniumFactory {
            override fun create(): SeleniumWebDriver = seleniumDriver
        }
        val driver = WebDriver(
            isStudio = false,
            isHeadless = true,
            screenSize = null,
            seleniumFactory = factory,
        )
        driver.open()

        val method = WebDriver::class.java
            .getDeclaredMethod("fetchCrossOriginIframeContent", String::class.java)
            .apply { isAccessible = true }

        val result = try {
            method.invoke(driver, iframeSrc)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

        assertThat(result).isNull()
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
    fun `contentDescriptor succeeds when screen recording failed to start`() {
        // Browserbase: the augmented RemoteWebDriver implements HasDevTools but the
        // CDP websocket cannot be established, so every devTools access throws.
        val seleniumDriver = mockk<SeleniumWebDriver>(
            relaxed = true,
            moreInterfaces = arrayOf(JavascriptExecutor::class, HasDevTools::class),
        )
        every { (seleniumDriver as HasDevTools).devTools } throws
            DevToolsException("Unable to create a DevTools connection")
        every { seleniumDriver.windowHandles } returns setOf("window-1")

        val executor = seleniumDriver as JavascriptExecutor
        every {
            executor.executeScript(match<String> { it.contains("getContentDescription") })
        } returns mapOf(
            "attributes" to mapOf("text" to "root", "bounds" to "[0,0][100,100]"),
            "children" to emptyList<Map<String, Any>>(),
        )

        val driver = WebDriver(
            isStudio = false,
            isHeadless = true,
            screenSize = null,
            seleniumFactory = object : SeleniumFactory {
                override fun create(): SeleniumWebDriver = seleniumDriver
            },
        )
        driver.open()

        // The caller (ArtifactsGenerator) still needs the failure signal.
        assertThat(runCatching { driver.startScreenRecording(blackholeSink()) }.isFailure).isTrue()

        // First hierarchy read of the session detects the initial window handle; the
        // failed recorder must not break it.
        val root = driver.contentDescriptor(excludeKeyboardElements = false)

        assertThat(root.attributes["text"]).isEqualTo("root")
    }

    @Test
    fun `contentDescriptor succeeds when recorder fails on window change`() {
        val seleniumDriver = mockk<SeleniumWebDriver>(
            relaxed = true,
            moreInterfaces = arrayOf(JavascriptExecutor::class, HasDevTools::class),
        )
        val devTools = mockk<DevTools>(relaxed = true)
        // devTools accesses in order: open(), startScreenRecording(), then the
        // re-attach on window change, which fails.
        every { (seleniumDriver as HasDevTools).devTools } returns
            devTools andThen devTools andThenThrows
            DevToolsException("Unable to create a DevTools connection")
        every { seleniumDriver.windowHandles } returns setOf("window-1")

        val executor = seleniumDriver as JavascriptExecutor
        every {
            executor.executeScript(match<String> { it.contains("getContentDescription") })
        } returns mapOf(
            "attributes" to mapOf("text" to "root", "bounds" to "[0,0][100,100]"),
            "children" to emptyList<Map<String, Any>>(),
        )

        val driver = WebDriver(
            isStudio = false,
            isHeadless = true,
            screenSize = null,
            seleniumFactory = object : SeleniumFactory {
                override fun create(): SeleniumWebDriver = seleniumDriver
            },
        )
        driver.open()
        driver.startScreenRecording(blackholeSink())

        val root = driver.contentDescriptor(excludeKeyboardElements = false)

        assertThat(root.attributes["text"]).isEqualTo("root")
    }
}
