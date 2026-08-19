package maestro.device

import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeDriverService
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.chromium.ChromiumDriverLogLevel
import org.openqa.selenium.devtools.HasDevTools
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Launches a detached Chromium browser for the WEB platform.
 *
 * Carved out of the legacy `maestro.drivers.CdpWebDriver.open()` (which implemented the now-deleted
 * `maestro.Driver` facade) during the device-core converge — the driver stack was deleted, but
 * [maestro.device.DeviceService.startDevice] still launches Chromium for the WEB platform. Only the
 * browser launch survives here; the CDP/JS automation the old driver did lives with the deleted
 * facade. `detach` keeps the browser open after this process lets the driver handle go.
 */
object WebDeviceLauncher {

    fun launchChromium(isHeadless: Boolean = false, screenSize: String? = null) {
        System.setProperty("webdriver.chrome.silentOutput", "true")
        System.setProperty(ChromeDriverService.CHROME_DRIVER_SILENT_OUTPUT_PROPERTY, "true")
        Logger.getLogger("org.openqa.selenium").level = Level.OFF
        Logger.getLogger("org.openqa.selenium.devtools.CdpVersionFinder").level = Level.OFF

        val driverService = ChromeDriverService.Builder()
            .withLogLevel(ChromiumDriverLogLevel.OFF)
            .build()

        val driver = ChromeDriver(
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
                    "profile.password_manager_leak_detection" to false
                )
                setExperimentalOption("prefs", chromePrefs)

                setExperimentalOption("detach", true)

                if (isHeadless) {
                    addArguments("--headless=new")
                    if (screenSize != null) {
                        addArguments("--window-size=" + screenSize.replace('x', ','))
                    } else {
                        addArguments("--window-size=1024,768")
                    }
                }
            }
        )

        try {
            (driver as? HasDevTools)
                ?.devTools
                ?.createSessionIfThereIsNotOne()
        } catch (e: Exception) {
            // Swallow the exception to avoid crashing the whole process.
            // Some implementations of Selenium do not support DevTools
            // and do not fail gracefully.
        }
    }
}
