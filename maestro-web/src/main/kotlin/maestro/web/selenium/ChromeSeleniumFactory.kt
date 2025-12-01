package maestro.web.selenium

import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeDriverService
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.chromium.ChromiumDriverLogLevel
import java.util.logging.Level
import java.util.logging.Logger

class ChromeSeleniumFactory(
    private val isHeadless: Boolean
) : SeleniumFactory {

    override fun create(): WebDriver {
        System.setProperty("webdriver.chrome.silentOutput", "true")
        System.setProperty(ChromeDriverService.CHROME_DRIVER_SILENT_OUTPUT_PROPERTY, "true")
        Logger.getLogger("org.openqa.selenium").level = Level.OFF
        Logger.getLogger("org.openqa.selenium.devtools.CdpVersionFinder").level = Level.OFF

        val driverService = ChromeDriverService.Builder()
            .withLogLevel(ChromiumDriverLogLevel.OFF)
            .build()

        return ChromeDriver(
            driverService,
            ChromeOptions().apply {
                addArguments("--remote-allow-origins=*")
                addArguments("--disable-search-engine-choice-screen")
                addArguments("--lang=en")

                // Disable password management
                addArguments("--password-store=basic")
                val chromePrefs = hashMapOf<String, Any>(
                    "credentials_enable_service" to false,
                    "profile.password_manager_enabled" to false,
                    "profile.password_manager_leak_detection" to false   // important one
                )
                setExperimentalOption("prefs", chromePrefs)

                if (isHeadless) {
                    addArguments("--headless=new")
                    addArguments("--window-size=1024,768")
                    setExperimentalOption("detach", true)
                }
            }
        )
    }

}