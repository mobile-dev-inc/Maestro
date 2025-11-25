package maestro.web.selenium

import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeDriverService
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.chromium.ChromiumDriverLogLevel
import org.openqa.selenium.remote.RemoteWebDriver
import org.slf4j.LoggerFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.logging.Level
import java.util.logging.Logger

class ChromeSeleniumFactory(
    private val isHeadless: Boolean,
    private val debuggingPort: Int = 9222,
    private val instanceId: String = "chromium"
) : SeleniumFactory {

    companion object {
        private val LOGGER = LoggerFactory.getLogger(ChromeSeleniumFactory::class.java)
    }

    override fun create(): WebDriver {
        LOGGER.info("ChromeSeleniumFactory.create() called for instanceId: $instanceId, debuggingPort: $debuggingPort")
        
        System.setProperty("webdriver.chrome.silentOutput", "true")
        System.setProperty(ChromeDriverService.CHROME_DRIVER_SILENT_OUTPUT_PROPERTY, "true")
        Logger.getLogger("org.openqa.selenium").level = Level.OFF
        Logger.getLogger("org.openqa.selenium.devtools.CdpVersionFinder").level = Level.OFF

        // Try to connect to existing Chrome instance first
        LOGGER.info("Attempting to connect to existing Chrome browser on port $debuggingPort...")
        val existingDriver = tryConnectToExistingChrome()
        if (existingDriver != null) {
            LOGGER.info("✅ Successfully connected to existing Chrome browser on port $debuggingPort")
            return existingDriver
        }

        // If no existing instance found, create a new one
        LOGGER.info("❌ No existing Chrome browser found on port $debuggingPort, creating new instance...")
        val driverService = ChromeDriverService.Builder()
            .withLogLevel(ChromiumDriverLogLevel.OFF)
            .build()

        // Create unique user data directory for this browser instance
        val userDataDir = File.createTempFile("maestro-chrome-${instanceId}-", "").apply {
            delete()
            mkdirs()
            deleteOnExit()
        }
        
        LOGGER.info("Creating new Chrome browser with user data dir: ${userDataDir.absolutePath}")

        val newDriver = ChromeDriver(
            driverService,
            ChromeOptions().apply {
                addArguments("--remote-allow-origins=*")
                addArguments("--disable-search-engine-choice-screen")
                addArguments("--lang=en")
                addArguments("--remote-debugging-port=$debuggingPort")
                addArguments("--user-data-dir=${userDataDir.absolutePath}")
                addArguments("--disable-web-security") // Helps with localhost testing
                addArguments("--disable-features=VizDisplayCompositor") // Stability improvement
                if (isHeadless) {
                    addArguments("--headless=new")
                    addArguments("--window-size=1024,768")
                    setExperimentalOption("detach", true)
                }
            }
        )
        
        LOGGER.info("✅ Successfully created new Chrome browser instance")
        return newDriver
    }

    /**
     * Attempts to connect to an existing Chrome browser instance running on the debugging port.
     * This enables browser session reuse, so Maestro can attach to browsers that are already 
     * logged in and have state preserved.
     */
    private fun tryConnectToExistingChrome(): WebDriver? {
        return try {
            LOGGER.debug("Checking Chrome DevTools Protocol accessibility at http://localhost:$debuggingPort/json/version")
            
            // Check if Chrome DevTools Protocol is accessible on the debugging port
            val url = URL("http://localhost:$debuggingPort/json/version")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 2000 // 2 second timeout
            connection.readTimeout = 2000

            val responseCode = connection.responseCode
            LOGGER.debug("DevTools Protocol response code: $responseCode")
            
            if (responseCode == 200) {
                LOGGER.info("Found existing Chrome browser on port $debuggingPort, attempting connection...")
                
                // Chrome is running and accessible, try to connect via Remote WebDriver
                val options = ChromeOptions().apply {
                    setExperimentalOption("debuggerAddress", "localhost:$debuggingPort")
                    addArguments("--remote-allow-origins=*")
                }
                
                val driver = RemoteWebDriver(options)
                LOGGER.info("Successfully connected to existing Chrome browser via RemoteWebDriver")
                
                // Log current browser state
                try {
                    val currentUrl = driver.currentUrl
                    val title = driver.title
                    LOGGER.info("Existing browser state - URL: $currentUrl, Title: $title")
                } catch (e: Exception) {
                    LOGGER.warn("Could not retrieve browser state: ${e.message}")
                }
                
                return driver
            } else {
                LOGGER.debug("Chrome DevTools Protocol not accessible (response code: $responseCode)")
                null
            }
        } catch (e: Exception) {
            // No existing Chrome instance found or connection failed
            LOGGER.debug("Failed to connect to existing Chrome browser: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

}