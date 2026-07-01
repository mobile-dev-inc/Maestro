package maestro.drivers

import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import maestro.web.selenium.SeleniumFactory
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.StaleElementReferenceException
import org.openqa.selenium.WebElement
import org.openqa.selenium.devtools.HasDevTools
import java.lang.reflect.InvocationTargetException
import java.util.ServiceConfigurationError
import org.openqa.selenium.WebDriver as SeleniumWebDriver

class WebDriverTest {

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
    fun `startScreenRecording fails fast when the driver does not support DevTools`() {
        // Browserbase's RemoteWebDriver isn't HasDevTools unless it advertises se:cdp.
        // Recording must surface a clear, intentional failure rather than a raw
        // ClassCastException deep inside the recorder (which leaves a 0-byte mp4).
        val seleniumDriver = mockk<SeleniumWebDriver>(relaxed = true) // no HasDevTools

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

        assertThrows<UnsupportedOperationException> {
            driver.startScreenRecording(Buffer())
        }
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
}
