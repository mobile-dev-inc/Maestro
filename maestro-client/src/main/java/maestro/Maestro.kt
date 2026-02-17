/*
 *
 *  Copyright (c) 2022 mobile.dev inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 *
 */

package maestro

import com.github.romankh3.image.comparison.ImageComparison
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.drivers.CdpWebDriver
import maestro.utils.MaestroTimer
import maestro.utils.ScreenshotUtils
import maestro.utils.SocketUtils
import okio.Buffer
import okio.Sink
import okio.buffer
import okio.sink
import okio.use
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.measureTimeMillis

@Suppress("unused", "MemberVisibilityCanBePrivate")
class Maestro(
    val driver: Driver,
) : AutoCloseable {

    val deviceName: String
        get() = driver.name()

    val cachedDeviceInfo by lazy {
        LOGGER.info("Getting device info")
        val deviceInfo = driver.deviceInfo()
        LOGGER.info("Got device info: $deviceInfo")
        deviceInfo
    }

    @Deprecated("This function should be removed and its usages refactored. See issue #2031")
    fun deviceInfo() = driver.deviceInfo()

    private var screenRecordingInProgress = false

    fun launchApp(
        appId: String,
        launchArguments: Map<String, Any> = emptyMap(),
        stopIfRunning: Boolean = true
    ) {
        LOGGER.info("Launching app $appId")

        if (stopIfRunning) {
            LOGGER.info("Stopping $appId app during launch")
            driver.stopApp(appId)
        }
        driver.launchApp(appId, launchArguments)
    }

    fun stopApp(appId: String) {
        LOGGER.info("Stopping app $appId")

        driver.stopApp(appId)
    }

    fun killApp(appId: String) {
        LOGGER.info("Killing app $appId")

        driver.killApp(appId)
    }

    fun clearAppState(appId: String) {
        LOGGER.info("Clearing app state $appId")

        driver.clearAppState(appId)
    }

    fun setPermissions(appId: String, permissions: Map<String, String>) {
        driver.setPermissions(appId, permissions)
    }

    fun clearKeychain() {
        LOGGER.info("Clearing keychain")

        driver.clearKeychain()
    }

    fun backPress() {
        LOGGER.info("Pressing back")

        driver.backPress()
        waitForAppToSettle()
    }

    fun hideKeyboard() {
        LOGGER.info("Hiding Keyboard")

        driver.hideKeyboard()
    }

    fun isKeyboardVisible(): Boolean {
        return driver.isKeyboardVisible()
    }

    fun swipe(
        swipeDirection: SwipeDirection? = null,
        start: String? = null,
        end: String? = null,
        startRelative: String? = null,
        endRelative: String? = null,
        duration: Long,
        waitToSettleTimeoutMs: Int? = null
    ) {
        val deviceInfo = deviceInfo()

        when {
            swipeDirection != null -> driver.swipe(swipeDirection, duration)

            start != null && end != null -> {
                val startPoint: Point?
                val endPoint: Point?

                val startPoints = start.split(",")
                    .map {
                        it.trim().toInt()
                    }
                startPoint = Point(startPoints[0], startPoints[1])

                val endPoints = end.split(",")
                    .map {
                        it.trim().toInt()
                    }
                endPoint = Point(endPoints[0], endPoints[1])
                driver.swipe(startPoint, endPoint, duration)
            }
            startRelative != null && endRelative != null -> {
                val startPoints = startRelative.replace("%", "")
                    .split(",").map { it.trim().toInt() }
                val startX = deviceInfo.widthGrid * startPoints[0] / 100
                val startY = deviceInfo.heightGrid * startPoints[1] / 100
                val start = Point(startX, startY)

                val endPoints = endRelative.replace("%", "")
                    .split(",").map { it.trim().toInt() }
                val endX = deviceInfo.widthGrid * endPoints[0] / 100
                val endY = deviceInfo.heightGrid * endPoints[1] / 100
                val end = Point(endX, endY)

                driver.swipe(start, end, duration)
            }
        }

        waitForAppToSettle(waitToSettleTimeoutMs = waitToSettleTimeoutMs)
    }

    fun swipe(swipeDirection: SwipeDirection, uiElement: UiElement, durationMs: Long, waitToSettleTimeoutMs: Int?) {
        LOGGER.info("Swiping ${swipeDirection.name} on element: $uiElement")
        driver.swipe(uiElement.bounds.center(), swipeDirection, durationMs)

        waitForAppToSettle(waitToSettleTimeoutMs = waitToSettleTimeoutMs)
    }

    fun swipeFromCenter(swipeDirection: SwipeDirection, durationMs: Long, waitToSettleTimeoutMs: Int?) {
        val deviceInfo = deviceInfo()

        LOGGER.info("Swiping ${swipeDirection.name} from center")
        val center = Point(x = deviceInfo.widthGrid / 2, y = deviceInfo.heightGrid / 2)
        driver.swipe(center, swipeDirection, durationMs)
        waitForAppToSettle(waitToSettleTimeoutMs = waitToSettleTimeoutMs)
    }

    fun scrollVertical() {
        LOGGER.info("Scrolling vertically")

        driver.scrollVertical()
        waitForAppToSettle()
    }

    fun tap(
        element: UiElement,
        initialHierarchy: ViewHierarchy,
        retryIfNoChange: Boolean = false,
        waitUntilVisible: Boolean = false,
        longPress: Boolean = false,
        appId: String? = null,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null
    ) {
        LOGGER.info("Tapping on element: ${tapRepeat ?: ""} $element")

        val hierarchyBeforeTap = waitForAppToSettle(initialHierarchy, appId, waitToSettleTimeoutMs) ?: initialHierarchy

        val center = (
                hierarchyBeforeTap
                    .refreshElement(element.treeNode)
                    ?.also { LOGGER.info("Refreshed element") }
                    ?.toUiElementOrNull()
                    ?: element
                ).bounds
            .center()
        performTap(
            x = center.x,
            y = center.y,
            retryIfNoChange = retryIfNoChange,
            longPress = longPress,
            initialHierarchy = hierarchyBeforeTap,
            tapRepeat = tapRepeat,
            waitToSettleTimeoutMs = waitToSettleTimeoutMs
        )

        if (waitUntilVisible) {
            val hierarchyAfterTap = viewHierarchy()

            if (hierarchyBeforeTap == hierarchyAfterTap
                && !hierarchyAfterTap.isVisible(element.treeNode)
            ) {
                LOGGER.info("Still no change in hierarchy. Wait until element is visible and try again.")

                val hierarchy = waitUntilVisible(element)

                tap(
                    element = element,
                    initialHierarchy = hierarchy,
                    retryIfNoChange = false,
                    waitUntilVisible = false,
                    longPress = longPress,
                    tapRepeat = tapRepeat
                )
            }
        }
    }

    fun tapOnRelative(
        percentX: Int,
        percentY: Int,
        retryIfNoChange: Boolean = false,
        longPress: Boolean = false,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null
    ) {
        val deviceInfo = driver.deviceInfo()
        val x = deviceInfo.widthGrid * percentX / 100
        val y = deviceInfo.heightGrid * percentY / 100
        tap(
            x = x,
            y = y,
            retryIfNoChange = retryIfNoChange,
            longPress = longPress,
            tapRepeat = tapRepeat,
            waitToSettleTimeoutMs = waitToSettleTimeoutMs
        )
    }

    fun tap(
        x: Int,
        y: Int,
        retryIfNoChange: Boolean = false,
        longPress: Boolean = false,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null
    ) {
        performTap(
            x = x,
            y = y,
            retryIfNoChange = retryIfNoChange,
            longPress = longPress,
            tapRepeat = tapRepeat,
            waitToSettleTimeoutMs = waitToSettleTimeoutMs
        )
    }

    private fun getNumberOfRetries(retryIfNoChange: Boolean): Int {
        return if (retryIfNoChange) 2 else 1
    }

    private fun performTap(
        x: Int,
        y: Int,
        retryIfNoChange: Boolean = false,
        longPress: Boolean = false,
        initialHierarchy: ViewHierarchy? = null,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null
    ) {
        val capabilities = driver.capabilities()

        if (Capability.FAST_HIERARCHY in capabilities) {
            hierarchyBasedTap(x, y, retryIfNoChange, longPress, initialHierarchy, tapRepeat, waitToSettleTimeoutMs)
        } else {
            screenshotBasedTap(x, y, retryIfNoChange, longPress, initialHierarchy, tapRepeat, waitToSettleTimeoutMs)
        }
    }

    private fun hierarchyBasedTap(
        x: Int,
        y: Int,
        retryIfNoChange: Boolean = false,
        longPress: Boolean = false,
        initialHierarchy: ViewHierarchy? = null,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null
    ) {
        LOGGER.info("Tapping at ($x, $y) using hierarchy based logic for wait")

        val hierarchyBeforeTap = initialHierarchy ?: viewHierarchy()

        val retries = getNumberOfRetries(retryIfNoChange)
        repeat(retries) {
            if (longPress) {
                driver.longPress(Point(x, y))
            } else if (tapRepeat != null) {
                for (i in 0 until tapRepeat.repeat) {

                    // subtract execution duration from tap delay
                    val duration = measureTimeMillis { driver.tap(Point(x, y)) }
                    val delay = if (duration >= tapRepeat.delay) 0 else tapRepeat.delay - duration

                    if (tapRepeat.repeat > 1) Thread.sleep(delay) // do not wait for single taps
                }
            } else driver.tap(Point(x, y))
            val hierarchyAfterTap = waitForAppToSettle(waitToSettleTimeoutMs = waitToSettleTimeoutMs)

            if (hierarchyAfterTap == null || hierarchyBeforeTap != hierarchyAfterTap) {
                LOGGER.info("Something has changed in the UI judging by view hierarchy. Proceed.")
                return
            }
        }
    }

    private fun screenshotBasedTap(
        x: Int,
        y: Int,
        retryIfNoChange: Boolean = false,
        longPress: Boolean = false,
        initialHierarchy: ViewHierarchy? = null,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null
    ) {
        LOGGER.info("Try tapping at ($x, $y) using hierarchy based logic for wait")

        val hierarchyBeforeTap = initialHierarchy ?: viewHierarchy()
        val screenshotBeforeTap: BufferedImage? = ScreenshotUtils.tryTakingScreenshot(driver)

        val retries = getNumberOfRetries(retryIfNoChange)
        repeat(retries) {
            if (longPress) {
                driver.longPress(Point(x, y))
            } else if (tapRepeat != null) {
                for (i in 0 until tapRepeat.repeat) {

                    // subtract execution duration from tap delay
                    val duration = measureTimeMillis { driver.tap(Point(x, y)) }
                    val delay = if (duration >= tapRepeat.delay) 0 else tapRepeat.delay - duration

                    if (tapRepeat.repeat > 1) Thread.sleep(delay) // do not wait for single taps
                }
            } else {
                driver.tap(Point(x, y))
            }
            val hierarchyAfterTap = waitForAppToSettle(waitToSettleTimeoutMs = waitToSettleTimeoutMs)

            if (hierarchyBeforeTap != hierarchyAfterTap) {
                LOGGER.info("Something have changed in the UI judging by view hierarchy. Proceed.")
                return
            }

            LOGGER.info("Tapping at ($x, $y) using screenshot based logic for wait")

            val screenshotAfterTap: BufferedImage? = ScreenshotUtils.tryTakingScreenshot(driver)
            if (screenshotBeforeTap != null &&
                screenshotAfterTap != null &&
                screenshotBeforeTap.width == screenshotAfterTap.width &&
                screenshotBeforeTap.height == screenshotAfterTap.height
            ) {
                val imageDiff = ImageComparison(
                    screenshotBeforeTap,
                    screenshotAfterTap
                ).compareImages().differencePercent

                if (imageDiff > SCREENSHOT_DIFF_THRESHOLD) {
                    LOGGER.info("Something have changed in the UI judging by screenshot (d=$imageDiff). Proceed.")
                    return
                } else {
                    LOGGER.info("Screenshots are not different enough (d=$imageDiff)")
                }
            } else {
                LOGGER.info("Skipping screenshot comparison")
            }

            LOGGER.info("Nothing changed in the UI.")
        }
    }

    private fun waitUntilVisible(element: UiElement): ViewHierarchy {
        var hierarchy = ViewHierarchy(TreeNode())
        repeat(10) {
            hierarchy = viewHierarchy()
            if (!hierarchy.isVisible(element.treeNode)) {
                LOGGER.info("Element is not visible yet. Waiting.")
                MaestroTimer.sleep(MaestroTimer.Reason.WAIT_UNTIL_VISIBLE, 1000)
            } else {
                LOGGER.info("Element became visible.")
                return hierarchy
            }
        }

        return hierarchy
    }

    fun pressKey(code: KeyCode, waitForAppToSettle: Boolean = true) {
        LOGGER.info("Pressing key $code")

        driver.pressKey(code)

        if (waitForAppToSettle) {
            waitForAppToSettle()
        }
    }

    fun viewHierarchy(excludeKeyboardElements: Boolean = false): ViewHierarchy {
        return ViewHierarchy.from(driver, excludeKeyboardElements)
    }

    fun findElementWithTimeout(
        timeoutMs: Long,
        filter: ElementFilter,
        viewHierarchy: ViewHierarchy? = null
    ): FindElementResult? {
        var hierarchy = viewHierarchy ?: ViewHierarchy(TreeNode())
        val element = MaestroTimer.withTimeout(timeoutMs) {
            hierarchy = viewHierarchy ?: viewHierarchy()
            filter(hierarchy.aggregate()).firstOrNull()
        }?.toUiElementOrNull()

        return if (element == null) {
            null
        } else {
            if (viewHierarchy != null) {
                hierarchy = ViewHierarchy(element.treeNode)
            }
            return FindElementResult(element, hierarchy)
        }
    }

    fun findElementsByOnDeviceQuery(
        timeoutMs: Long,
        query: OnDeviceElementQuery
    ): OnDeviceElementQueryResult? {
        return MaestroTimer.withTimeout(timeoutMs) {
            val elements = driver.queryOnDeviceElements(query)

            OnDeviceElementQueryResult(
                elements = elements.mapNotNull { it.toUiElementOrNull() },
            )
        }
    }

    fun allElementsMatching(filter: ElementFilter): List<TreeNode> {
        return filter(viewHierarchy().aggregate())
    }

    fun waitForAppToSettle(
        initialHierarchy: ViewHierarchy? = null,
        appId: String? = null,
        waitToSettleTimeoutMs: Int? = null
    ): ViewHierarchy? {
        return driver.waitForAppToSettle(initialHierarchy, appId, waitToSettleTimeoutMs)
    }

    fun inputText(text: String) {
        LOGGER.info("Inputting text: $text")

        driver.inputText(text)
        waitForAppToSettle()
    }

    fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        LOGGER.info("Opening link $link for app: $appId with autoVerify config as $autoVerify")

        driver.openLink(link, appId, autoVerify, browser)
        waitForAppToSettle()
    }

    fun addMedia(fileNames: List<String>) {
        val mediaFiles = fileNames.map { File(it) }
        driver.addMedia(mediaFiles)
    }

    override fun close() {
        driver.close()
    }

    @Deprecated("Use takeScreenshot(Sink, Boolean) instead")
    fun takeScreenshot(outFile: File, compressed: Boolean) {
        LOGGER.info("Taking screenshot to a file: $outFile")

        val absoluteOutFile = outFile.absoluteFile

        if (absoluteOutFile.parentFile.exists() || absoluteOutFile.parentFile.mkdirs()) {
            outFile
                .sink()
                .buffer()
                .use {
                    ScreenshotUtils.takeScreenshot(it, compressed, driver)
                }
        } else {
            throw MaestroException.DestinationIsNotWritable(
                "Failed to create directory for screenshot: ${absoluteOutFile.parentFile}"
            )
        }
    }

    fun takeScreenshot(sink: Sink, compressed: Boolean, bounds: Bounds? = null) {
        if (bounds == null) {
            LOGGER.info("Taking screenshot")
            sink
                .buffer()
                .use {
                    ScreenshotUtils.takeScreenshot(it, compressed, driver)
                }
        } else {
            LOGGER.info("Taking screenshot (cropped to bounds)")
            val (x, y, width, height) = bounds

            val originalImage = Buffer().apply {
                ScreenshotUtils.takeScreenshot(this, compressed, driver)
            }.let { buffer ->
                buffer.inputStream().use { ImageIO.read(it) }
            }

            val info = cachedDeviceInfo
            val scale = if (info.heightGrid > 0) {
                info.heightPixels.toDouble() / info.heightGrid
            } else {
                1.0
            }
            val startX = (x * scale).toInt().coerceIn(0, originalImage.width)
            val startY = (y * scale).toInt().coerceIn(0, originalImage.height)
            val cropWidthPx = (width * scale).toInt()
                .coerceIn(0, originalImage.width - startX)
            val cropHeightPx = (height * scale).toInt()
                .coerceIn(0, originalImage.height - startY)

            if (cropWidthPx <= 0 || cropHeightPx <= 0) {
                throw MaestroException.AssertionFailure(
                    message = "Cannot crop screenshot: invalid dimensions (width: $cropWidthPx, height: $cropHeightPx).",
                    hierarchyRoot = viewHierarchy(excludeKeyboardElements = false).root,
                    debugMessage = "Bounds (grid units) x=$x, y=$y, width=$width, height=$height with scale=$scale produced non-positive crop size."
                )
            }

            val croppedImage = originalImage.getSubimage(
                startX, startY, cropWidthPx, cropHeightPx
            )

            sink
                .buffer()
                .use {
                    ImageIO.write(croppedImage, "png", it.outputStream())
                }
        }
    }

    fun startScreenRecording(out: Sink): ScreenRecording {
        LOGGER.info("Starting screen recording")

        if (screenRecordingInProgress) {
            LOGGER.info("Screen recording not started: Already in progress")
            return object : ScreenRecording {
                override fun close() {
                    // No-op
                }
            }
        }
        screenRecordingInProgress = true

        LOGGER.info("Starting screen recording")
        val screenRecording = driver.startScreenRecording(out)
        val startTimestamp = System.currentTimeMillis()
        return object : ScreenRecording {
            override fun close() {
                LOGGER.info("Stopping screen recording")
                // Ensure minimum screen recording duration of 3 seconds.
                // This addresses an edge case where the launch command completes too quickly.
                val durationPadding = 3000 - (System.currentTimeMillis() - startTimestamp)
                if (durationPadding > 0) {
                    Thread.sleep(durationPadding)
                }
                screenRecording.close()
                screenRecordingInProgress = false
            }
        }
    }

    fun setLocation(latitude: String, longitude: String) {
        LOGGER.info("Setting location: ($latitude, $longitude)")

        driver.setLocation(latitude.toDouble(), longitude.toDouble())
    }

    fun setOrientation(orientation: DeviceOrientation, waitForAppToSettle: Boolean = true) {
        LOGGER.info("Setting orientation: $orientation")

        driver.setOrientation(orientation)

        if (waitForAppToSettle) {
            waitForAppToSettle()
        }
    }

    fun eraseText(charactersToErase: Int) {
        LOGGER.info("Erasing $charactersToErase characters")

        driver.eraseText(charactersToErase)
    }

    fun waitForAnimationToEnd(timeout: Long?) {
        @Suppress("NAME_SHADOWING")
        val timeout = timeout ?: ANIMATION_TIMEOUT_MS
        LOGGER.info("Waiting for animation to end with timeout $timeout")

        ScreenshotUtils.waitUntilScreenIsStatic(timeout, SCREENSHOT_DIFF_THRESHOLD, driver)
    }

    fun setProxy(
        host: String = SocketUtils.localIp(),
        port: Int
    ) {
        LOGGER.info("Setting proxy: $host:$port")

        driver.setProxy(host, port)
    }

    fun resetProxy() {
        LOGGER.info("Resetting proxy")

        driver.resetProxy()
    }

    fun isShutDown(): Boolean {
        return driver.isShutdown()
    }

    fun isUnicodeInputSupported(): Boolean {
        return driver.isUnicodeInputSupported()
    }

    fun isAirplaneModeEnabled(): Boolean {
        return driver.isAirplaneModeEnabled()
    }

    fun setAirplaneModeState(enabled: Boolean) {
        driver.setAirplaneMode(enabled)
    }

    fun setAndroidChromeDevToolsEnabled(enabled: Boolean) {
        driver.setAndroidChromeDevToolsEnabled(enabled)
    }

    companion object {

        private val LOGGER = LoggerFactory.getLogger(Maestro::class.java)

        private const val SCREENSHOT_DIFF_THRESHOLD = 0.005 // 0.5%
        private const val ANIMATION_TIMEOUT_MS: Long = 15000

        fun ios(driver: Driver, openDriver: Boolean = true): Maestro {
            if (openDriver) {
                driver.open()
            }
            return Maestro(driver)
        }

        fun android(driver: Driver, openDriver: Boolean = true): Maestro {
            if (openDriver) {
                driver.open()
            }
            return Maestro(driver)
        }

        fun web(
            isStudio: Boolean,
            isHeadless: Boolean,
            screenSize: String?,
        ): Maestro {
            // Check that JRE is at least 11
            val version = System.getProperty("java.version")
            if (version.startsWith("1.")) {
                val majorVersion = version.substring(2, 3).toInt()
                if (majorVersion < 11) {
                    throw MaestroException.UnsupportedJavaVersion(
                        "Maestro Web requires Java 11 or later. Current version: $version"
                    )
                }
            }

            val driver = CdpWebDriver(
                isStudio = isStudio,
                isHeadless = isHeadless,
                screenSize = screenSize,
            )
            driver.open()
            return Maestro(driver)
        }
    }
}
