package maestro.web.cdp

import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver

interface CdpClientFactory {
    fun create(seleniumDriver: WebDriver): CdpClient
}

class LocalCdpClientFactory : CdpClientFactory {
    override fun create(seleniumDriver: WebDriver): CdpClient {
        val options = (seleniumDriver as ChromeDriver)
            .capabilities
            .getCapability("goog:chromeOptions") as Map<String, Any>
        val debuggerAddress = options["debuggerAddress"] as String
        val parts = debuggerAddress.split(":")
        return CdpClient(host = parts[0], port = parts[1].toInt())
    }
}
