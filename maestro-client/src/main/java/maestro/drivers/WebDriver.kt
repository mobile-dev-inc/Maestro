package maestro.drivers

import maestro.Capability
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.Maestro
import maestro.Platform
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.utils.ScreenshotUtils
import okio.Sink
import maestro.NamedSource
import okio.buffer
import org.openqa.selenium.By
import org.openqa.selenium.JavascriptExecutor
import org.openqa.selenium.Keys
import org.openqa.selenium.OutputType
import org.openqa.selenium.TakesScreenshot
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeDriverService
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.chromium.ChromiumDriverLogLevel
import org.openqa.selenium.interactions.Actions
import org.openqa.selenium.interactions.PointerInput
import org.openqa.selenium.remote.RemoteWebDriver
import org.openqa.selenium.support.ui.WebDriverWait
import java.io.File
import java.time.Duration
import java.util.Random
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger

class WebDriver(val isStudio: Boolean) : Driver {

    private var seleniumDriver: org.openqa.selenium.WebDriver? = null
    private var maestroWebScript: String? = null

    init {
        Maestro::class.java.getResourceAsStream("/maestro-web.js")?.let {
            it.bufferedReader().use { br ->
                maestroWebScript = br.readText()
            }
        } ?: error("Could not read maestro web script")
    }

    override fun name(): String {
        return "Chromium Desktop Browser (Experimental)"
    }

    override fun open() {
        System.setProperty("webdriver.chrome.silentOutput", "true");
        System.setProperty(ChromeDriverService.CHROME_DRIVER_SILENT_OUTPUT_PROPERTY, "true");
        Logger.getLogger("org.openqa.selenium").level = Level.OFF;
        Logger.getLogger("org.openqa.selenium.devtools.CdpVersionFinder").level = Level.OFF;

        val driverService = ChromeDriverService.Builder()
            .withLogLevel(ChromiumDriverLogLevel.OFF)
            .build()

        seleniumDriver = ChromeDriver(
            driverService,
            ChromeOptions().apply {
                addArguments("--remote-allow-origins=*")
                if (isStudio) {
                    addArguments("--headless=new")
                    addArguments("--window-size=1024,768")
                }
            }
        )

        if (isStudio) {
            seleniumDriver?.get("https://maestro.mobile.dev")
        }
    }

    private fun ensureOpen(): org.openqa.selenium.WebDriver {
        return seleniumDriver ?: error("Driver is not open")
    }

    private fun executeJS(js: String): Any? {
        val executor = seleniumDriver as JavascriptExecutor

        try {
            executor.executeScript("$maestroWebScript")
            Thread.sleep(100)
            return executor.executeScript(js)
        } catch (e: Exception) {
            if (e.message?.contains("getContentDescription") == true) {
                return executeJS(js)
            }
            return null
        }
    }

    private fun scrollToPoint(point: Point): Long {
        ensureOpen()
        val windowHeight = executeJS("return window.innerHeight") as Long

        if (point.y >= 0 && point.y.toLong() <= windowHeight) return 0L

        val scrolledPixels =
            executeJS("const delta = ${point.y} - Math.floor(window.innerHeight / 2); window.scrollBy({ top: delta, left: 0, behavior: 'smooth' }); return delta") as Long
        sleep(3000L)
        return scrolledPixels
    }

    private fun sleep(ms: Long) {
        Thread.sleep(ms)
    }

    private fun scroll(top: String, left: String) {
        executeJS("window.scroll({ top: $top, left: $left, behavior: 'smooth' });")
    }

    private fun random(start: Int, end: Int): Int {
        return Random().nextInt((end + 1) - start) + start
    }

    override fun close() {
        seleniumDriver?.quit()
        seleniumDriver = null
    }

    override fun deviceInfo(): DeviceInfo {
        val driver = ensureOpen()

        val windowSize = driver.manage().window().size

        return DeviceInfo(
            platform = Platform.WEB,
            widthPixels = windowSize.width,
            heightPixels = windowSize.height,
            widthGrid = windowSize.width,
            heightGrid = windowSize.height,
        )
    }

    override fun launchApp(
        appId: String,
        launchArguments: Map<String, Any>,
        sessionId: UUID?,
    ) {
        open()
        val driver = ensureOpen()

        driver.manage().timeouts().implicitlyWait(Duration.ofMillis(5000))
        val wait = WebDriverWait(driver, Duration.ofSeconds(30L))

        driver.get(appId)
        wait.until { (it as JavascriptExecutor).executeScript("return document.readyState") == "complete" }
    }

    override fun stopApp(appId: String) {
        val driver = ensureOpen()

        driver.close()
    }

    override fun killApp(appId: String) {
        // On Web there is no Process Death like on Android so this command will be a synonym to the stop command
        stopApp(appId)
    }

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
        ensureOpen()

        // retrieve view hierarchy from DOM
        val contentDesc = executeJS("return window.maestro.getContentDescription()")
            ?: throw IllegalStateException("Could not retrieve hierarchy through maestro.getContentDescription()")

        // parse into TreeNodes
        fun parse(domRepresentation: Map<String, Any>): TreeNode {
            val attrs = domRepresentation["attributes"] as Map<String, Any>

            val attributes = mutableMapOf(
                "text" to attrs["text"] as String,
                "bounds" to attrs["bounds"] as String,
            )
            if (attrs.containsKey("resource-id") && attrs["resource-id"] != null) {
                attributes["resource-id"] = attrs["resource-id"] as String
            }
            val children = domRepresentation["children"] as List<Map<String, Any>>

            return TreeNode(attributes = attributes, children = children.map { parse(it) })
        }


        return parse(contentDesc as Map<String, Any>)
    }

    override fun clearAppState(appId: String) {
        // Do nothing
    }

    override fun clearKeychain() {
        // Do nothing
    }

    override fun tap(point: Point) {
        val driver = ensureOpen()
        val pixelsScrolled = scrollToPoint(point)

        val mouse = PointerInput(PointerInput.Kind.MOUSE, "default mouse")
        val actions = org.openqa.selenium.interactions.Sequence(mouse, 1)
            .addAction(mouse.createPointerMove(Duration.ofMillis(400), PointerInput.Origin.viewport(), point.x, point.y - pixelsScrolled.toInt()))

        (driver as RemoteWebDriver).perform(listOf(actions))

        Actions(driver).click().build().perform()
    }

    override fun longPress(point: Point) {
        val driver = ensureOpen()

        val mouse = PointerInput(PointerInput.Kind.MOUSE, "default mouse")
        val actions = org.openqa.selenium.interactions.Sequence(mouse, 0)
            .addAction(mouse.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), point.x, point.y))
        (driver as RemoteWebDriver).perform(listOf(actions))

        Actions(driver).clickAndHold().pause(3000L).release().build().perform()
    }

    override fun pressKey(code: KeyCode) {
        val driver = ensureOpen()

        val xPath = executeJS("return window.maestro.createXPathFromElement(document.activeElement)") as String
        val element = driver.findElement(By.ByXPath(xPath))
        val key = KeyCode.mapToSeleniumKey(code)
            ?: throw IllegalArgumentException("Keycode $code is not supported on web")
        element.sendKeys(key)
    }

    override fun scrollVertical() {
        scroll("window.scrollY + Math.round(window.innerHeight / 2)", "window.scrollX")
    }

    override fun isKeyboardVisible(): Boolean {
        TODO("Not yet implemented")
    }

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        // TODO validate implementation and ensure it works properly
        val driver = ensureOpen()

        val finger = PointerInput(PointerInput.Kind.TOUCH, "finger")
        val swipe = org.openqa.selenium.interactions.Sequence(finger, 1)
        swipe.addAction(
            finger.createPointerMove(
                Duration.ofMillis(0),
                PointerInput.Origin.viewport(),
                start.x,
                start.y
            )
        )
        swipe.addAction(finger.createPointerDown(PointerInput.MouseButton.LEFT.asArg()))
        swipe.addAction(
            finger.createPointerMove(
                Duration.ofMillis(durationMs),
                PointerInput.Origin.viewport(),
                end.x,
                end.y
            )
        )
        swipe.addAction(finger.createPointerUp(PointerInput.MouseButton.LEFT.asArg()))
        (driver as RemoteWebDriver).perform(listOf(swipe))
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        // TODO validate implementation and ensure it works properly
        when (swipeDirection) {
            SwipeDirection.UP -> scroll("window.scrollY + Math.round(window.innerHeight / 2)", "window.scrollX")
            SwipeDirection.DOWN -> scroll("window.scrollY - Math.round(window.innerHeight / 2)", "window.scrollX")
            SwipeDirection.LEFT -> scroll("window.scrollY", "window.scrollX + Math.round(window.innerWidth / 2)")
            SwipeDirection.RIGHT -> scroll("window.scrollY", "window.scrollX - Math.round(window.innerWidth / 2)")
        }
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        // Ignoring elementPoint to enable a rudimentary implementation of scrollUntilVisible for web
        swipe(direction, durationMs)
    }

    fun swipe(start: Point, end: Point) {
        TODO("Not yet implemented")
    }

    override fun backPress() {
        val driver = ensureOpen()
        driver.navigate().back()
    }

    override fun inputText(text: String) {
        val driver = ensureOpen()

        val xPath = executeJS("return window.maestro.createXPathFromElement(document.activeElement)") as String
        val element = driver.findElement(By.ByXPath(xPath))
        for (c in text.toCharArray()) {
            element.sendKeys("$c")
            sleep(random(20, 100).toLong())
        }
    }

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        val driver = ensureOpen()

        driver.get(link)
    }

    override fun hideKeyboard() {
        // no-op on web
        return
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        val driver = ensureOpen()

        val src = (driver as TakesScreenshot).getScreenshotAs(OutputType.FILE)
        out.buffer().use { it.write(src.readBytes()) }
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        TODO("Not yet implemented")
    }

    override fun setLocation(latitude: Double, longitude: Double) {
        TODO("Not yet implemented")
    }

    override fun eraseText(charactersToErase: Int) {
        val driver = ensureOpen()

        val xPath = executeJS("return window.maestro.createXPathFromElement(document.activeElement)") as String
        val element = driver.findElement(By.ByXPath(xPath))
        for (i in 0 until charactersToErase) {
            element.sendKeys(Keys.BACK_SPACE)
            sleep(random(20, 50).toLong())
        }

        sleep(1000)
    }

    override fun setProxy(host: String, port: Int) {
        TODO("Not yet implemented")
    }

    override fun resetProxy() {
        TODO("Not yet implemented")
    }

    override fun isShutdown(): Boolean {
        close()
        return true
    }

    override fun isUnicodeInputSupported(): Boolean {
        return true
    }

    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy? {
        return ScreenshotUtils.waitForAppToSettle(initialHierarchy, this)
    }

    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        return ScreenshotUtils.waitUntilScreenIsStatic(timeoutMs, SCREENSHOT_DIFF_THRESHOLD, this)
    }

    override fun capabilities(): List<Capability> {
        return listOf(
            Capability.FAST_HIERARCHY
        )
    }

    override fun setPermissions(appId: String, permissions: Map<String, String>) {
        // no-op for web
    }

    override fun addMedia(mediaFiles: List<File>) {
        // noop for web
    }

    override fun isAirplaneModeEnabled(): Boolean {
        TODO("Not yet implemented")
    }

    override fun setAirplaneMode(enabled: Boolean) {
        TODO("Not yet implemented")
    }

    companion object {
        private const val SCREENSHOT_DIFF_THRESHOLD = 0.005
    }
}
