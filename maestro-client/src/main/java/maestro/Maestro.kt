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
import dadb.Dadb
import maestro.Filters.asFilter
import maestro.UiElement.Companion.toUiElement
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.drivers.AndroidDriver
import maestro.utils.MaestroTimer
import maestro.utils.SocketUtils
import okio.Buffer
import okio.Sink
import okio.buffer
import okio.sink
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

@Suppress("unused", "MemberVisibilityCanBePrivate")
class Maestro(private val driver: Driver) : AutoCloseable {

    private val cachedDeviceInfo by lazy {
        fetchDeviceInfo()
    }

    fun deviceName(): String {
        return driver.name()
    }

    fun deviceInfo(): DeviceInfo {
        return cachedDeviceInfo
    }

    private fun fetchDeviceInfo(): DeviceInfo {
        LOGGER.info("Getting device info")

        return driver.deviceInfo()
    }

    fun launchApp(appId: String, stopIfRunning: Boolean = true) {
        LOGGER.info("Launching app $appId")

        if (stopIfRunning) {
            driver.stopApp(appId)
        }
        driver.launchApp(appId)
    }

    fun stopApp(appId: String) {
        LOGGER.info("Stopping app $appId")

        driver.stopApp(appId)
    }

    fun clearAppState(appId: String) {
        LOGGER.info("Clearing app state $appId")

        driver.clearAppState(appId)
    }

    fun clearKeychain() {
        LOGGER.info("Clearing keychain")

        driver.clearKeychain()
    }

    fun pullAppState(appId: String, outFile: File) {
        LOGGER.info("Pulling app state: $appId")

        driver.pullAppState(appId, outFile)
    }

    fun pushAppState(appId: String, stateFile: File) {
        LOGGER.info("Pushing app state: $appId")

        driver.pushAppState(appId, stateFile)
    }

    fun backPress() {
        LOGGER.info("Pressing back")

        driver.backPress()
        waitForAppToSettle()
    }

    fun hideKeyboard() {
        LOGGER.info("Hiding Keyboard")

        driver.hideKeyboard()
        waitForAppToSettle()
    }

    fun swipe(swipeDirection: SwipeDirection? = null, start: Point? = null, end: Point? = null, duration: Long) {
        when {
            swipeDirection != null -> driver.swipe(swipeDirection, duration)
            start != null && end != null -> driver.swipe(start, end, duration)
        }

        waitForAppToSettle()
    }

    fun swipe(swipeDirection: SwipeDirection, uiElement: UiElement, durationMs: Long) {
        LOGGER.info("Swiping ${swipeDirection.name} on element: $uiElement")
        driver.swipe(uiElement.bounds.center(), swipeDirection, durationMs)

        waitForAppToSettle()
    }

    fun scrollVertical() {
        LOGGER.info("Scrolling vertically")

        driver.scrollVertical()
        waitForAppToSettle()
    }

    fun tap(element: TreeNode) {
        tap(element.toUiElement())
    }

    fun tap(
        element: UiElement,
        retryIfNoChange: Boolean = true,
        waitUntilVisible: Boolean = false,
        longPress: Boolean = false,
    ) {
        LOGGER.info("Tapping on element: $element")

        val hierarchyBeforeTap = waitForAppToSettle()

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
        )

        if (waitUntilVisible) {
            val hierarchyAfterTap = viewHierarchy()

            if (hierarchyBeforeTap == hierarchyAfterTap
                && !hierarchyAfterTap.isVisible(element.treeNode)
            ) {
                LOGGER.info("Still no change in hierarchy. Wait until element is visible and try again.")

                waitUntilVisible(element)
                tap(
                    element,
                    retryIfNoChange = false,
                    waitUntilVisible = false,
                    longPress = longPress,
                )
            }
        }
    }

    fun tapOnRelative(
        percentX: Int,
        percentY: Int,
        retryIfNoChange: Boolean = true,
        longPress: Boolean = false,
    ) {
        val x = cachedDeviceInfo.widthGrid * percentX / 100
        val y = cachedDeviceInfo.heightGrid * percentY / 100
        tap(
            x = x,
            y = y,
            retryIfNoChange = retryIfNoChange,
            longPress = longPress,
        )
    }

    fun tap(
        x: Int,
        y: Int,
        retryIfNoChange: Boolean = true,
        longPress: Boolean = false,
    ) {
        performTap(
            x = x,
            y = y,
            retryIfNoChange = retryIfNoChange,
            longPress = longPress,
        )
    }

    private fun performTap(
        x: Int,
        y: Int,
        retryIfNoChange: Boolean = true,
        longPress: Boolean = false,
        initialHierarchy: ViewHierarchy? = null,
    ) {
        LOGGER.info("Tapping at ($x, $y)")

        val hierarchyBeforeTap = initialHierarchy ?: viewHierarchy()
        val screenshotBeforeTap: BufferedImage? = tryTakingScreenshot()

        val retries = getNumberOfRetries(retryIfNoChange)
        repeat(retries) {
            if (longPress) {
                driver.longPress(Point(x, y))
            } else {
                driver.tap(Point(x, y))
            }
            val hierarchyAfterTap = waitForAppToSettle()

            if (hierarchyBeforeTap != hierarchyAfterTap) {
                LOGGER.info("Something have changed in the UI judging by view hierarchy. Proceed.")
                return
            }

            val screenshotAfterTap: BufferedImage? = tryTakingScreenshot()
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

    private fun tryTakingScreenshot() = try {
        ImageIO.read(takeScreenshot().inputStream())
    } catch (e: Exception) {
        LOGGER.warn("Failed to take screenshot", e)
        null
    }

    private fun waitUntilVisible(element: UiElement) {
        repeat(10) {
            if (!viewHierarchy().isVisible(element.treeNode)) {
                LOGGER.info("Element is not visible yet. Waiting.")
                MaestroTimer.sleep(MaestroTimer.Reason.WAIT_UNTIL_VISIBLE, 1000)
            } else {
                LOGGER.info("Element became visible.")
                return
            }
        }
    }

    private fun getNumberOfRetries(retryIfNoChange: Boolean): Int {
        return if (retryIfNoChange) 2 else 1
    }

    fun pressKey(code: KeyCode, waitForAppToSettle: Boolean = true) {
        LOGGER.info("Pressing key $code")

        driver.pressKey(code)

        if (waitForAppToSettle) {
            waitForAppToSettle()
        }
    }

    fun findElementByText(text: String, timeoutMs: Long): UiElement {
        LOGGER.info("Looking for element by text: $text (timeout $timeoutMs)")

        return findElementWithTimeout(timeoutMs, Filters.textMatches(text).asFilter())
            ?: throw MaestroException.ElementNotFound(
                "No element with text: $text",
                viewHierarchy().root
            )
    }

    fun findElementByRegexp(regex: Regex, timeoutMs: Long): UiElement {
        LOGGER.info("Looking for element by regex: ${regex.pattern} (timeout $timeoutMs)")

        return findElementWithTimeout(timeoutMs, Filters.textMatches(regex).asFilter())
            ?: throw MaestroException.ElementNotFound(
                "No element that matches regex: $regex",
                viewHierarchy().root
            )
    }

    fun viewHierarchy(): ViewHierarchy {
        return ViewHierarchy.from(driver)
    }

    fun findElementByIdRegex(regex: Regex, timeoutMs: Long): UiElement {
        LOGGER.info("Looking for element by id regex: ${regex.pattern} (timeout $timeoutMs)")

        return findElementWithTimeout(timeoutMs, Filters.idMatches(regex))
            ?: throw MaestroException.ElementNotFound(
                "No element has id that matches regex $regex",
                viewHierarchy().root
            )
    }

    fun findElementBySize(width: Int?, height: Int?, tolerance: Int?, timeoutMs: Long): UiElement? {
        LOGGER.info("Looking for element by size: $width x $height (tolerance $tolerance) (timeout $timeoutMs)")

        return findElementWithTimeout(timeoutMs, Filters.sizeMatches(width, height, tolerance).asFilter())
    }

    fun findElementWithTimeout(
        timeoutMs: Long,
        filter: ElementFilter,
    ): UiElement? {
        return MaestroTimer.withTimeout(timeoutMs) {
            val rootNode = driver.contentDescriptor()

            filter(rootNode.aggregate())
                .firstOrNull()
        }?.toUiElementOrNull()
    }

    fun allElementsMatching(filter: ElementFilter): List<TreeNode> {
        return filter(viewHierarchy().aggregate())
    }

    fun waitForAppToSettle(): ViewHierarchy {
        var latestHierarchy = viewHierarchy()
        repeat(10) {
            val hierarchyAfter = viewHierarchy()
            if (latestHierarchy == hierarchyAfter) {
                return hierarchyAfter
            }
            latestHierarchy = hierarchyAfter

            MaestroTimer.sleep(MaestroTimer.Reason.WAIT_TO_SETTLE, 200)
        }

        return latestHierarchy
    }

    fun inputText(text: String) {
        driver.inputText(text)
        waitForAppToSettle()
    }

    fun openLink(link: String) {
        driver.openLink(link)
        waitForAppToSettle()
    }

    override fun close() {
        driver.close()
    }

    fun takeScreenshot(outFile: File) {
        LOGGER.info("Taking screenshot: $outFile")

        val absoluteOutFile = outFile.absoluteFile

        if (absoluteOutFile.parentFile.exists() || absoluteOutFile.parentFile.mkdirs()) {
            outFile
                .sink()
                .buffer()
                .use {
                    takeScreenshot(it)
                }
        } else {
            throw MaestroException.DestinationIsNotWritable(
                "Failed to create directory for screenshot: ${absoluteOutFile.parentFile}"
            )
        }
    }

    fun startScreenRecording(out: Sink): ScreenRecording {
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
            }
        }
    }

    fun takeScreenshot(out: Sink) {
        LOGGER.info("Taking screenshot to output sink")

        driver.takeScreenshot(out)
    }

    fun takeScreenshot(): ByteArray {
        LOGGER.info("Taking screenshot to byte array")

        val buffer = Buffer()
        takeScreenshot(buffer)

        return buffer.readByteArray()
    }

    fun setLocation(latitude: Double, longitude: Double) {
        LOGGER.info("Setting location: ($latitude, $longitude)")

        driver.setLocation(latitude, longitude)
    }

    fun eraseText(charactersToErase: Int) {
        LOGGER.info("Erasing $charactersToErase characters")

        driver.eraseText(charactersToErase)
    }

    fun waitForAnimationToEnd(timeout: Long?) {
        val timeout = timeout ?: ANIMATION_TIMEOUT_MS
        LOGGER.info("Waiting for animation to end with timeout $timeout")

        MaestroTimer.retryUntilTrue(timeout) {
            val startScreenshot: BufferedImage? = tryTakingScreenshot()
            val endScreenshot: BufferedImage? = tryTakingScreenshot()

            if (startScreenshot != null &&
                endScreenshot != null &&
                startScreenshot.width == endScreenshot.width &&
                startScreenshot.height == endScreenshot.height
            ) {
                val imageDiff = ImageComparison(
                    startScreenshot,
                    endScreenshot
                ).compareImages().differencePercent

                return@retryUntilTrue imageDiff <= SCREENSHOT_DIFF_THRESHOLD
            }

            return@retryUntilTrue false
        }
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

    companion object {

        private val LOGGER = LoggerFactory.getLogger(Maestro::class.java)
        private const val SCREENSHOT_DIFF_THRESHOLD = 0.005 // 0.5%
        private const val ANIMATION_TIMEOUT_MS: Long = 15000

        fun ios(
            driver: Driver,
            openDriver: Boolean = true,
        ): Maestro {
            if (openDriver) {
                driver.open()
            }
            return Maestro(driver)
        }

        fun android(
            dadb: Dadb,
            hostPort: Int = 7001,
            openDriver: Boolean = true,
        ): Maestro {
            val driver = AndroidDriver(dadb, hostPort = hostPort)
            if (openDriver) {
                driver.open()
            }
            return Maestro(driver)
        }
    }
}
