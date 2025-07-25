package maestro.web.selenium

import org.openqa.selenium.WebDriver

interface SeleniumFactory {

    fun create(): WebDriver

}