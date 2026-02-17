package maestro.drivers

import CdpClient
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import maestro.Capability
import maestro.DeviceInfo
import maestro.DeviceOrientation
import maestro.Driver
import maestro.KeyCode
import maestro.Maestro
import maestro.OnDeviceElementQuery
import maestro.Platform
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.ViewHierarchy
import maestro.utils.ScreenshotUtils
import maestro.web.record.JcodecVideoEncoder
import maestro.web.record.WebScreenRecorder
import okio.Sink
import okio.buffer
import org.openqa.selenium.By
import org.openqa.selenium.Keys
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeDriverService
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.chromium.ChromiumDriverLogLevel
import org.openqa.selenium.devtools.HasDevTools
import org.openqa.selenium.devtools.v144.emulation.Emulation
import org.openqa.selenium.interactions.Actions
import org.openqa.selenium.interactions.PointerInput
import org.openqa.selenium.interactions.Sequence
import org.openqa.selenium.remote.RemoteWebDriver
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Duration
import java.util.*
import java.util.logging.Level
import java.util.logging.Logger


private const val SYNTHETIC_COORDINATE_SPACE_OFFSET = 100000

class CdpWebDriver(
    val isStudio: Boolean,
    private val isHeadless: Boolean = false,
    private val screenSize: String?
) : Driver {

    private lateinit var cdpClient: CdpClient

    private var seleniumDriver: org.openqa.selenium.WebDriver? = null
    private var maestroWebScript: String? = null
    private var lastSeenWindowHandles = setOf<String>()
    private var injectedArguments: Map<String, Any> = emptyMap()

    private var webScreenRecorder: WebScreenRecorder? = null

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
        seleniumDriver = createSeleniumDriver()

        try {
            seleniumDriver
                ?.let { it as? HasDevTools }
                ?.devTools
                ?.createSessionIfThereIsNotOne()
        } catch (e: Exception) {
            // Swallow the exception to avoid crashing the whole process.
            // Some implementations of Selenium do not support DevTools
            // and do not fail gracefully.
        }

        if (isStudio) {
            seleniumDriver?.get("https://maestro.mobile.dev")
        }
    }

    private fun createSeleniumDriver(): WebDriver {
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
                    "profile.password_manager_leak_detection" to false   // important one
                )
                setExperimentalOption("prefs", chromePrefs)

                if (isHeadless) {
                    addArguments("--headless=new")
                    if(screenSize != null){
                        addArguments("--window-size=" + screenSize.replace('x',','))
                    }
                    else{
                        addArguments("--window-size=1024,768")
                    }
                    setExperimentalOption("detach", true)
                }
            }
        )

        val options = driver.capabilities.getCapability("goog:chromeOptions") as Map<String, Any>
        val debuggerAddress = options["debuggerAddress"] as String
        val parts = debuggerAddress.split(":")

        cdpClient = CdpClient(
            host = parts[0],
            port = parts[1].toInt()
        )

        return driver
    }

    private fun ensureOpen(): org.openqa.selenium.WebDriver {
        return seleniumDriver ?: error("Driver is not open")
    }

    private fun executeJS(js: String): Any? {
        return runBlocking {
            try {
                val target = cdpClient.listTargets().first()

                cdpClient.evaluate("$maestroWebScript", target)

                injectedArguments.forEach { (key, value) ->
                    cdpClient.evaluate("$key = '$value'", target)
                }

                Thread.sleep(100)

                var resultStr = cdpClient.evaluate(js, target)

                // Convert from string to Map<String, Any> if needed
                return@runBlocking jacksonObjectMapper().readValue(resultStr, Any::class.java)
            } catch (e: Exception) {
                if (e.message?.contains("getContentDescription") == true) {
                    return@runBlocking executeJS(js)
                } else {
                    LOGGER.error("Failed to execute JS", e)
                }
                return@runBlocking null
            }
        }
    }

    private fun scrollToPoint(point: Point): Long {
        ensureOpen()
        val windowHeight = executeJS("window.innerHeight") as Int

        if (point.y >= 0 && point.y.toLong() <= windowHeight) return 0L

        val scrolledPixels =
            executeJS("() => {const delta = ${point.y} - Math.floor(window.innerHeight / 2); window.scrollBy({ top: delta, left: 0, behavior: 'smooth' }); return delta}()") as Int
        sleep(3000L)
        return scrolledPixels.toLong()
    }

    private fun sleep(ms: Long) {
        Thread.sleep(ms)
    }

    private fun scroll(top: String, left: String) {
        executeJS("window.scroll({ top: $top, left: $left, behavior: 'smooth' })")
    }

    private fun random(start: Int, end: Int): Int {
        return Random().nextInt((end + 1) - start) + start
    }

    override fun close() {
        injectedArguments = emptyMap()

        try {
            seleniumDriver?.quit()
            webScreenRecorder?.close()
        } catch (e: Exception) {
            // Swallow the exception to avoid crashing the whole process
        }

        seleniumDriver = null
        lastSeenWindowHandles = setOf()
        webScreenRecorder = null
    }

    override fun deviceInfo(): DeviceInfo {
        val width = executeJS("window.innerWidth") as Int
        val height = executeJS("window.innerHeight") as Int

        return DeviceInfo(
            platform = Platform.WEB,
            widthPixels = width,
            heightPixels = height,
            widthGrid = width,
            heightGrid = height,
        )
    }

    override fun launchApp(
        appId: String,
        launchArguments: Map<String, Any>,
    ) {
        injectedArguments = injectedArguments + launchArguments

        runBlocking {
            val target = cdpClient.listTargets().first()
            cdpClient.openUrl(appId, target)
        }
    }

    override fun stopApp(appId: String) {
        // Not supported at the moment.
        // Simply calling driver.close() can kill the Selenium session, rendering
        // the driver inoperable.
    }

    override fun killApp(appId: String) {
        // On Web there is no Process Death like on Android so this command will be a synonym to the stop command
        stopApp(appId)
    }

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
        ensureOpen()

        detectWindowChange()

        // retrieve view hierarchy from DOM
        // There are edge cases where executeJS returns null, and we cannot get the hierarchy. In this situation
        // we retry multiple times until throwing an error eventually. (See issue #1936)
        var contentDesc: Any? = null
        var retry = 0
        while (contentDesc == null) {
            contentDesc = executeJS("window.maestro.getContentDescription()")
            if (contentDesc == null) {
                retry++
            }
            if (retry == RETRY_FETCHING_CONTENT_DESCRIPTION) {
                throw IllegalStateException("Could not retrieve hierarchy through maestro.getContentDescription() (tried $retry times")
            }
        }

        val root = parseDomAsTreeNodes(contentDesc as Map<String, Any>)
        seleniumDriver?.currentUrl?.let { url ->
            root.attributes["url"] = url
        }
        return root
    }

    fun parseDomAsTreeNodes(domRepresentation: Map<String, Any>): TreeNode {
        val attrs = domRepresentation["attributes"] as Map<String, Any>

        val attributes = mutableMapOf(
            "text" to attrs["text"] as String,
            "bounds" to attrs["bounds"] as String,
        )
        if (attrs.containsKey("resource-id") && attrs["resource-id"] != null) {
            attributes["resource-id"] = attrs["resource-id"] as String
        }
        if (attrs.containsKey("selected") && attrs["selected"] != null) {
            attributes["selected"] = (attrs["selected"] as Boolean).toString()
        }
        if (attrs.containsKey("synthetic") && attrs["synthetic"] != null) {
            attributes["synthetic"] = (attrs["synthetic"] as Boolean).toString()
        }
        if (attrs.containsKey("ignoreBoundsFiltering") && attrs["ignoreBoundsFiltering"] != null) {
            attributes["ignoreBoundsFiltering"] = (attrs["ignoreBoundsFiltering"] as Boolean).toString()
        }

        val children = domRepresentation["children"] as List<Map<String, Any>>

        return TreeNode(attributes = attributes, children = children.map { parseDomAsTreeNodes(it) })
    }

    private fun detectWindowChange() {
        // Checks whether there are any new window handles available and, if so, switches Selenium driver focus to it
        val driver = ensureOpen()

        if (lastSeenWindowHandles != driver.windowHandles) {
            val newHandles = driver.windowHandles - lastSeenWindowHandles
            lastSeenWindowHandles = driver.windowHandles

            if (newHandles.isNotEmpty()) {
                val newHandle = newHandles.first()
                LOGGER.info("Detected a window change, switching to new window handle $newHandle")

                driver.switchTo().window(newHandle)

                webScreenRecorder?.onWindowChange()
            }
        }
    }

    override fun clearAppState(appId: String) {
        // Do nothing
    }

    override fun clearKeychain() {
        // Do nothing
    }

    override fun tap(point: Point) {
        val driver = ensureOpen()

        if (point.x >= SYNTHETIC_COORDINATE_SPACE_OFFSET && point.y >= SYNTHETIC_COORDINATE_SPACE_OFFSET) {
            tapOnSyntheticCoordinateSpace(point)
            return
        }

        val pixelsScrolled = scrollToPoint(point)

        val mouse = PointerInput(PointerInput.Kind.MOUSE, "default mouse")
        val actions = Sequence(mouse, 1)
            .addAction(
                mouse.createPointerMove(
                    Duration.ofMillis(400),
                    PointerInput.Origin.viewport(),
                    point.x,
                    point.y - pixelsScrolled.toInt()
                )
            )

        (driver as RemoteWebDriver).perform(listOf(actions))

        Actions(driver).click().build().perform()
    }

    private fun tapOnSyntheticCoordinateSpace(point: Point) {
        val elements = contentDescriptor()

        val hit = ViewHierarchy.from(this, true)
            .getElementAt(elements, point.x, point.y)

        if (hit == null) {
            return
        }

        if (hit.attributes["synthetic"] != "true") {
            return
        }

        executeJS("window.maestro.tapOnSyntheticElement(${point.x}, ${point.y})")
    }

    override fun longPress(point: Point) {
        val driver = ensureOpen()

        val mouse = PointerInput(PointerInput.Kind.MOUSE, "default mouse")
        val actions = Sequence(mouse, 0)
            .addAction(mouse.createPointerMove(Duration.ZERO, PointerInput.Origin.viewport(), point.x, point.y))
        (driver as RemoteWebDriver).perform(listOf(actions))

        Actions(driver).clickAndHold().pause(3000L).release().build().perform()
    }

    override fun pressKey(code: KeyCode) {
        val driver = ensureOpen()

        val xPath = executeJS("window.maestro.createXPathFromElement(document.activeElement)") as String
        val element = driver.findElement(By.ByXPath(xPath))
        val key = mapToSeleniumKey(code)
        element.sendKeys(key)
    }

    private fun mapToSeleniumKey(code: KeyCode): Keys {
        return when (code) {
            KeyCode.ENTER -> Keys.ENTER
            KeyCode.BACKSPACE -> Keys.BACK_SPACE
            else -> error("Keycode $code is not supported on web")
        }
    }

    override fun scrollVertical() {
        // Check if this is a Flutter web app
        val isFlutter = executeJS("window.maestro.isFlutterApp()") as? Boolean ?: false
        
        if (isFlutter) {
            // Use Flutter-specific smooth animated scrolling
            executeJS("window.maestro.smoothScrollFlutter('UP', 500)")
        } else {
            // Use standard scroll for regular web pages
            scroll("window.scrollY + Math.round(window.innerHeight / 2)", "window.scrollX")
        }
    }

    override fun isKeyboardVisible(): Boolean {
        return false
    }

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        val driver = ensureOpen()

        val finger = PointerInput(PointerInput.Kind.TOUCH, "finger")
        val swipe = Sequence(finger, 1)
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
        val isFlutter = executeJS("window.maestro.isFlutterApp()") as? Boolean ?: false
        
        if (isFlutter) {
            // Flutter web: Use smooth animated scrolling with easing
            executeJS("window.maestro.smoothScrollFlutter('${swipeDirection.name}', $durationMs)")
        } else {
            // HTML web: Use standard window scrolling
            when (swipeDirection) {
                SwipeDirection.UP -> scroll("window.scrollY + Math.round(window.innerHeight / 2)", "window.scrollX")
                SwipeDirection.DOWN -> scroll("window.scrollY - Math.round(window.innerHeight / 2)", "window.scrollX")
                SwipeDirection.LEFT -> scroll("window.scrollY", "window.scrollX + Math.round(window.innerWidth / 2)")
                SwipeDirection.RIGHT -> scroll("window.scrollY", "window.scrollX - Math.round(window.innerWidth / 2)")
            }
        }
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        // Ignoring elementPoint to enable a rudimentary implementation of scrollUntilVisible for web
        swipe(direction, durationMs)
    }

    override fun backPress() {
        val driver = ensureOpen()
        driver.navigate().back()
    }

    override fun inputText(text: String) {
        val driver = ensureOpen()

        val xPath = executeJS("window.maestro.createXPathFromElement(document.activeElement)") as String
        val element = driver.findElement(By.ByXPath(xPath))
        for (c in text.toCharArray()) {
            element.sendKeys("$c")
            sleep(random(20, 100).toLong())
        }
    }

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        val driver = ensureOpen()

        driver.get(if (link.startsWith("http")) link else "https://$link")
    }

    override fun hideKeyboard() {
        // no-op on web
        return
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        runBlocking {
            val target = cdpClient.listTargets().first()
            val bytes = cdpClient.captureScreenshot(target)

            out.buffer().use { it.write(bytes) }
        }
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        val driver = ensureOpen()
        webScreenRecorder = WebScreenRecorder(
            JcodecVideoEncoder(),
            driver
        )
        webScreenRecorder?.startScreenRecording(out)

        return object : ScreenRecording {
            override fun close() {
                webScreenRecorder?.close()
            }
        }
    }

    override fun setLocation(latitude: Double, longitude: Double) {
        val driver = ensureOpen() as HasDevTools

        driver.devTools.createSessionIfThereIsNotOne()

        driver.devTools.send(
            Emulation.setGeolocationOverride(
                Optional.of(latitude),
                Optional.of(longitude),
                Optional.of(0.0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()
            )
        )
    }

    override fun setOrientation(orientation: DeviceOrientation) {
        // No op
    }

    override fun eraseText(charactersToErase: Int) {
        val driver = ensureOpen()

        val xPath = executeJS("window.maestro.createXPathFromElement(document.activeElement)") as String
        val element = driver.findElement(By.ByXPath(xPath))
        for (i in 0 until charactersToErase) {
            element.sendKeys(Keys.BACK_SPACE)
            sleep(random(20, 50).toLong())
        }

        sleep(1000)
    }

    override fun setProxy(host: String, port: Int) {
        // Do nothing
    }

    override fun resetProxy() {
        // Do nothing
    }

    override fun isShutdown(): Boolean {
        close()
        return true
    }

    override fun isUnicodeInputSupported(): Boolean {
        return true
    }

    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy {
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
        return false
    }

    override fun setAirplaneMode(enabled: Boolean) {
        // Do nothing
    }

    override fun queryOnDeviceElements(query: OnDeviceElementQuery): List<TreeNode> {
        return when (query) {
            is OnDeviceElementQuery.Css -> queryCss(query)
            else -> super.queryOnDeviceElements(query)
        }
    }

    private fun queryCss(query: OnDeviceElementQuery.Css): List<TreeNode> {
        ensureOpen()

        val jsResult: Any? = executeJS("window.maestro.queryCss('${query.css}')")

        if (jsResult == null) {
            return emptyList()
        }

        if (jsResult is List<*>) {
            return jsResult
                .mapNotNull { it as? Map<*, *> }
                .map { parseDomAsTreeNodes(it as Map<String, Any>) }
        } else {
            LOGGER.error("Unexpected result type from queryCss: ${jsResult.javaClass.name}")
            return emptyList()
        }
    }

    companion object {
        private const val SCREENSHOT_DIFF_THRESHOLD = 0.005
        private const val RETRY_FETCHING_CONTENT_DESCRIPTION = 10

        private val LOGGER = LoggerFactory.getLogger(CdpWebDriver::class.java)
    }
}

fun main() {
    val driver = CdpWebDriver(isStudio = false, isHeadless = false, screenSize = null)
    driver.open()

    try {
        driver.launchApp("https://example.com", emptyMap())
        println(driver.contentDescriptor())

        println(driver.deviceInfo())
    } finally {
        driver.close()
    }
}