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
import maestro.device.DeviceOrientation
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.yield
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
    suspend fun deviceInfo() = runInterruptible(Dispatchers.IO) { driver.deviceInfo() }

    private var screenRecordingInProgress = false

    suspend fun launchApp(
        appId: String,
        launchArguments: Map<String, Any> = emptyMap(),
        stopIfRunning: Boolean = true
    ) = runInterruptible(Dispatchers.IO) {
        LOGGER.info("Launching app $appId")

        if (stopIfRunning) {
            LOGGER.info("Stopping $appId app during launch")
            driver.stopApp(appId)
        }
        driver.launchApp(appId, launchArguments)
    }

    suspend fun stopApp(appId: String) = runInterruptible(Dispatchers.IO) {
        LOGGER.info("Stopping app $appId")

        driver.stopApp(appId)
    }

    suspend fun killApp(appId: String) = runInterruptible(Dispatchers.IO) {
        LOGGER.info("Killing app $appId")

        driver.killApp(appId)
    }

    suspend fun clearAppState(appId: String) = runInterruptible(Dispatchers.IO) {
        LOGGER.info("Clearing app state $appId")

        driver.clearAppState(appId)
    }

    suspend fun setPermissions(appId: String, permissions: Map<String, String>) = runInterruptible(Dispatchers.IO) {
        driver.setPermissions(appId, permissions)
    }

    suspend fun clearKeychain() = runInterruptible(Dispatchers.IO) {
        LOGGER.info("Clearing keychain")

        driver.clearKeychain()
    }

    suspend fun backPress() {
        LOGGER.info("Pressing back")

        runInterruptible(Dispatchers.IO) { driver.backPress() }
        waitForAppToSettle()
    }

    suspend fun hideKeyboard() = runInterruptible(Dispatchers.IO) {
        LOGGER.info("Hiding Keyboard")

        driver.hideKeyboard()
    }

    suspend fun isKeyboardVisible(): Boolean = runInterruptible(Dispatchers.IO) {
        driver.isKeyboardVisible()
    }

    suspend fun swipe(
        swipeDirection: SwipeDirection? = null,
        startPoint: Point? = null,
        endPoint: Point? = null,
        startRelative: String? = null,
        endRelative: String? = null,
        duration: Long,
        waitToSettleTimeoutMs: Int? = null
    ) {
        val deviceInfo = deviceInfo()

        runInterruptible(Dispatchers.IO) {
            when {
                swipeDirection != null -> driver.swipe(swipeDirection, duration)
                startPoint != null && endPoint != null -> driver.swipe(startPoint, endPoint, duration)
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
        }

        waitForAppToSettle(waitToSettleTimeoutMs = waitToSettleTimeoutMs)
    }

    suspend fun swipe(swipeDirection: SwipeDirection, uiElement: UiElement, durationMs: Long, waitToSettleTimeoutMs: Int?) {
        LOGGER.info("Swiping ${swipeDirection.name} on element: $uiElement")
        runInterruptible(Dispatchers.IO) { driver.swipe(uiElement.bounds.center(), swipeDirection, durationMs) }

        waitForAppToSettle(waitToSettleTimeoutMs = waitToSettleTimeoutMs)
    }

    suspend fun swipeFromCenter(swipeDirection: SwipeDirection, durationMs: Long, waitToSettleTimeoutMs: Int?) {
        val deviceInfo = deviceInfo()

        LOGGER.info("Swiping ${swipeDirection.name} from center")
        val center = Point(x = deviceInfo.widthGrid / 2, y = deviceInfo.heightGrid / 2)
        runInterruptible(Dispatchers.IO) { driver.swipe(center, swipeDirection, durationMs) }
        waitForAppToSettle(waitToSettleTimeoutMs = waitToSettleTimeoutMs)
    }

    suspend fun scrollVertical() {
        LOGGER.info("Scrolling vertically")

        runInterruptible(Dispatchers.IO) { driver.scrollVertical() }
        waitForAppToSettle()
    }

    suspend fun tap(
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

    suspend fun tapOnRelative(
        percentX: Int,
        percentY: Int,
        retryIfNoChange: Boolean = false,
        longPress: Boolean = false,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null
    ) {
        val deviceInfo = runInterruptible(Dispatchers.IO) { driver.deviceInfo() }
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

    suspend fun tap(
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

    private suspend fun getNumberOfRetries(retryIfNoChange: Boolean): Int {
        return if (retryIfNoChange) 2 else 1
    }

    private suspend fun performTap(
        x: Int,
        y: Int,
        retryIfNoChange: Boolean = false,
        longPress: Boolean = false,
        initialHierarchy: ViewHierarchy? = null,
        tapRepeat: TapRepeat? = null,
        waitToSettleTimeoutMs: Int? = null
    ) {
        val capabilities = runInterruptible(Dispatchers.IO) { driver.capabilities() }

        if (Capability.FAST_HIERARCHY in capabilities) {
            hierarchyBasedTap(x, y, retryIfNoChange, longPress, initialHierarchy, tapRepeat, waitToSettleTimeoutMs)
        } else {
            screenshotBasedTap(x, y, retryIfNoChange, longPress, initialHierarchy, tapRepeat, waitToSettleTimeoutMs)
        }
    }

    private suspend fun hierarchyBasedTap(
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
                runInterruptible(Dispatchers.IO) { driver.longPress(Point(x, y)) }
            } else if (tapRepeat != null) {
                for (i in 0 until tapRepeat.repeat) {

                    // subtract execution duration from tap delay
                    val duration = measureTimeMillis {
                        runInterruptible(Dispatchers.IO) { driver.tap(Point(x, y)) }
                    }
                    val tapDelay = if (duration >= tapRepeat.delay) 0 else tapRepeat.delay - duration

                    if (tapRepeat.repeat > 1) delay(tapDelay) // do not wait for single taps
                }
            } else {
                runInterruptible(Dispatchers.IO) { driver.tap(Point(x, y)) }
            }
            val hierarchyAfterTap = waitForAppToSettle(waitToSettleTimeoutMs = waitToSettleTimeoutMs)

            if (hierarchyAfterTap == null || hierarchyBeforeTap != hierarchyAfterTap) {
                LOGGER.info("Something has changed in the UI judging by view hierarchy. Proceed.")
                return
            }
        }
    }

    private suspend fun screenshotBasedTap(
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
        val screenshotBeforeTap: BufferedImage? = runInterruptible(Dispatchers.IO) { ScreenshotUtils.tryTakingScreenshot(driver) }

        val retries = getNumberOfRetries(retryIfNoChange)
        repeat(retries) {
            if (longPress) {
                runInterruptible(Dispatchers.IO) { driver.longPress(Point(x, y)) }
            } else if (tapRepeat != null) {
                for (i in 0 until tapRepeat.repeat) {

                    // subtract execution duration from tap delay
                    val duration = measureTimeMillis {
                        runInterruptible(Dispatchers.IO) { driver.tap(Point(x, y)) }
                    }
                    val tapDelay = if (duration >= tapRepeat.delay) 0 else tapRepeat.delay - duration

                    if (tapRepeat.repeat > 1) delay(tapDelay) // do not wait for single taps
                }
            } else {
                runInterruptible(Dispatchers.IO) { driver.tap(Point(x, y)) }
            }
            val hierarchyAfterTap = waitForAppToSettle(waitToSettleTimeoutMs = waitToSettleTimeoutMs)

            if (hierarchyBeforeTap != hierarchyAfterTap) {
                LOGGER.info("Something have changed in the UI judging by view hierarchy. Proceed.")
                return
            }

            LOGGER.info("Tapping at ($x, $y) using screenshot based logic for wait")

            val screenshotAfterTap: BufferedImage? = runInterruptible(Dispatchers.IO) { ScreenshotUtils.tryTakingScreenshot(driver) }
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

    private suspend fun waitUntilVisible(element: UiElement): ViewHierarchy {
        var hierarchy = ViewHierarchy(TreeNode())
        repeat(10) {
            yield() // cooperative cancellation checkpoint
            hierarchy = viewHierarchy()
            if (hierarchy.isVisible(element.treeNode)) {
                LOGGER.info("Element became visible.")
                return hierarchy
            }
            LOGGER.info("Element is not visible yet. Waiting.")
            delay(1000)
        }
        return hierarchy
    }

    suspend fun pressKey(code: KeyCode, waitForAppToSettle: Boolean = true) {
        LOGGER.info("Pressing key $code")

        runInterruptible(Dispatchers.IO) { driver.pressKey(code) }

        if (waitForAppToSettle) {
            waitForAppToSettle()
        }
    }

    suspend fun viewHierarchy(excludeKeyboardElements: Boolean = false): ViewHierarchy = runInterruptible(Dispatchers.IO) {
        ViewHierarchy.from(driver, excludeKeyboardElements)
    }

    suspend fun findElementWithTimeout(
        timeoutMs: Long,
        filter: ElementFilter,
        initialHierarchy: ViewHierarchy? = null
    ): FindElementResult? {
        var hierarchy = initialHierarchy ?: ViewHierarchy(TreeNode())

        val found = MaestroTimer.withTimeoutSuspend(timeoutMs) {
            hierarchy = initialHierarchy ?: runInterruptible(Dispatchers.IO) {
                ViewHierarchy.from(driver, false)
            }
            filter(hierarchy.aggregate()).firstOrNull()
        }

        val uiElement = found?.toUiElementOrNull() ?: return null
        if (initialHierarchy != null) {
            hierarchy = ViewHierarchy(uiElement.treeNode)
        }
        return FindElementResult(uiElement, hierarchy)
    }

    suspend fun findElementsByOnDeviceQuery(
        timeoutMs: Long,
        query: OnDeviceElementQuery
    ): OnDeviceElementQueryResult? {
        return MaestroTimer.withTimeoutSuspend(timeoutMs) {
            val result = runInterruptible(Dispatchers.IO) {
                val elements = driver.queryOnDeviceElements(query)
                OnDeviceElementQueryResult(
                    elements = elements.mapNotNull { it.toUiElementOrNull() },
                )
            }
            if (result.elements.isNotEmpty()) result else null
        }
    }

    suspend fun allElementsMatching(filter: ElementFilter): List<TreeNode> {
        return filter(viewHierarchy().aggregate())
    }

    suspend fun waitForAppToSettle(
        initialHierarchy: ViewHierarchy? = null,
        appId: String? = null,
        waitToSettleTimeoutMs: Int? = null
    ): ViewHierarchy? = runInterruptible(Dispatchers.IO) {
        driver.waitForAppToSettle(initialHierarchy, appId, waitToSettleTimeoutMs)
    }

    suspend fun inputText(text: String) {
        LOGGER.info("Inputting text: $text")

        runInterruptible(Dispatchers.IO) { driver.inputText(text) }
        waitForAppToSettle()
    }

    suspend fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        LOGGER.info("Opening link $link for app: $appId with autoVerify config as $autoVerify")

        runInterruptible(Dispatchers.IO) { driver.openLink(link, appId, autoVerify, browser) }
        waitForAppToSettle()
    }

    suspend fun addMedia(fileNames: List<String>) = runInterruptible(Dispatchers.IO) {
        val mediaFiles = fileNames.map { File(it) }
        driver.addMedia(mediaFiles)
    }

    override fun close() {
        driver.close()
    }

    @Deprecated("Use takeScreenshot(Sink, Boolean) instead")
    suspend fun takeScreenshot(outFile: File, compressed: Boolean) = runInterruptible(Dispatchers.IO) {
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

    suspend fun takeScreenshot(sink: Sink, compressed: Boolean, bounds: Bounds? = null) {
        if (bounds == null) {
            LOGGER.info("Taking screenshot")
            runInterruptible(Dispatchers.IO) {
                sink
                    .buffer()
                    .use {
                        ScreenshotUtils.takeScreenshot(it, compressed, driver)
                    }
            }
        } else {
            LOGGER.info("Taking screenshot (cropped to bounds)")
            val (x, y, width, height) = bounds

            val originalImage = runInterruptible(Dispatchers.IO) {
                Buffer().apply {
                    ScreenshotUtils.takeScreenshot(this, compressed, driver)
                }.let { buffer ->
                    buffer.inputStream().use { ImageIO.read(it) }
                }
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

    suspend fun startScreenRecording(out: Sink): ScreenRecording {
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
        val screenRecording = runInterruptible(Dispatchers.IO) { driver.startScreenRecording(out) }
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

    suspend fun setLocation(latitude: String, longitude: String) = runInterruptible(Dispatchers.IO) {
        LOGGER.info("Setting location: ($latitude, $longitude)")

        driver.setLocation(latitude.toDouble(), longitude.toDouble())
    }

    suspend fun setOrientation(orientation: DeviceOrientation, waitForAppToSettle: Boolean = true) {
        LOGGER.info("Setting orientation: $orientation")

        runInterruptible(Dispatchers.IO) { driver.setOrientation(orientation) }

        if (waitForAppToSettle) {
            waitForAppToSettle()
        }
    }

    suspend fun eraseText(charactersToErase: Int) = runInterruptible(Dispatchers.IO) {
        LOGGER.info("Erasing $charactersToErase characters")

        driver.eraseText(charactersToErase)
    }

    suspend fun waitForAnimationToEnd(timeout: String?) = runInterruptible(Dispatchers.IO) {
        @Suppress("NAME_SHADOWING")
        val timeout = timeout?.toLong() ?: ANIMATION_TIMEOUT_MS
        LOGGER.info("Waiting for animation to end with timeout $timeout")

        ScreenshotUtils.waitUntilScreenIsStatic(timeout, SCREENSHOT_DIFF_THRESHOLD, driver)
    }

    suspend fun setProxy(
        host: String = SocketUtils.localIp(),
        port: Int
    ) = runInterruptible(Dispatchers.IO) {
        LOGGER.info("Setting proxy: $host:$port")

        driver.setProxy(host, port)
    }

    suspend fun resetProxy() = runInterruptible(Dispatchers.IO) {
        LOGGER.info("Resetting proxy")

        driver.resetProxy()
    }

    suspend fun isShutDown(): Boolean = runInterruptible(Dispatchers.IO) {
        driver.isShutdown()
    }

    suspend fun isUnicodeInputSupported(): Boolean = runInterruptible(Dispatchers.IO) {
        driver.isUnicodeInputSupported()
    }

    suspend fun isAirplaneModeEnabled(): Boolean = runInterruptible(Dispatchers.IO) {
        driver.isAirplaneModeEnabled()
    }

    suspend fun setAirplaneModeState(enabled: Boolean) = runInterruptible(Dispatchers.IO) {
        driver.setAirplaneMode(enabled)
    }

    suspend fun setAndroidChromeDevToolsEnabled(enabled: Boolean) = runInterruptible(Dispatchers.IO) {
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
