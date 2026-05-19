package maestro.drivers

import io.mockk.every
import io.mockk.mockk
import maestro.web.selenium.SeleniumFactory
import org.junit.jupiter.api.Test
import org.openqa.selenium.devtools.HasDevTools
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
}
