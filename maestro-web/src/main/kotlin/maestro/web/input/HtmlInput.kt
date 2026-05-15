package maestro.web.input

import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.WebElement

fun WebElement.isHtmlDateInput(): Boolean {
    return tagName.equals("input", ignoreCase = true) &&
        getAttribute("type")?.equals("date", ignoreCase = true) == true
}

fun JavascriptExecutor.inputHtmlDate(element: WebElement, text: String): Boolean {
    val normalizedDate = HtmlDateInputFormatter.normalize(text) ?: return false

    executeScript(
        """
        const element = arguments[0];
        const value = arguments[1];
        const valueSetter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set;
        valueSetter.call(element, value);
        element.dispatchEvent(new Event('input', { bubbles: true }));
        element.dispatchEvent(new Event('change', { bubbles: true }));
        """.trimIndent(),
        element,
        normalizedDate,
    )

    return true
}
