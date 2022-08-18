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

import dadb.Dadb
import io.grpc.ManagedChannelBuilder
import ios.idb.IdbIOSDevice
import maestro.UiElement.Companion.toUiElement
import maestro.drivers.AndroidDriver
import maestro.drivers.IOSDriver
import maestro.utils.ViewUtils
import dadb.Dadb
import io.grpc.ManagedChannel
import io.grpc.ManagedChannelBuilder
import ios.idb.IdbIOSDevice
import org.slf4j.LoggerFactory
import java.io.File

@Suppress("unused", "MemberVisibilityCanBePrivate")
class Maestro(private val driver: Driver) : AutoCloseable {

    fun deviceName(): String {
        return driver.name()
    }

    fun deviceInfo(): DeviceInfo {
        LOGGER.info("Getting device info")

        return driver.deviceInfo()
    }

    fun launchApp(appId: String) {
        LOGGER.info("Launching app $appId")

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

    fun swipe(start: Point, end: Point) {
        LOGGER.info("Swiping from (${start.x},${start.y}) to (${end.x},${end.y})")

        driver.swipe(start, end)
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
        waitUntilVisible: Boolean = true,
    ) {
        LOGGER.info("Tapping on element: $element")

        if (waitUntilVisible) {
            waitUntilVisible(element)
        }

        val center = element.bounds.center()
        tap(center.x, center.y, retryIfNoChange)
    }

    fun tap(
        x: Int,
        y: Int,
        retryIfNoChange: Boolean = true,
    ) {
        LOGGER.info("Tapping at ($x, $y)")

        val hierarchyBeforeTap = viewHierarchy()

        val retries = getNumberOfRetries(retryIfNoChange)
        repeat(retries) {
            driver.tap(Point(x, y))
            waitForAppToSettle()

            val hierarchyAfterTap = viewHierarchy()

            if (hierarchyBeforeTap != hierarchyAfterTap) {
                LOGGER.info("Something have changed in the UI. Proceed.")
                return
            }

            LOGGER.info("Nothing changed in the UI.")
        }

        if (retryIfNoChange) {
            LOGGER.info("Attempting to tap again since there was no change in the UI")
            tap(x, y, false)
        }
    }

    private fun waitUntilVisible(element: UiElement) {
        repeat(10) {
            if (!ViewUtils.isVisible(viewHierarchy(), element.treeNode)) {
                LOGGER.info("Element is not visible yet. Waiting.")
                MaestroTimer.sleep(MaestroTimer.Reason.WAIT_UNTIL_VISIBLE, 1000)
            } else {
                LOGGER.info("Element became visible.")
                return
            }
        }
    }

    private fun getNumberOfRetries(retryIfNoChange: Boolean): Int {
        return if (retryIfNoChange) 3 else 1
    }

    fun findElementByText(text: String, timeoutMs: Long): UiElement {
        LOGGER.info("Looking for element by text: $text (timeout $timeoutMs)")

        return findElementWithTimeout(timeoutMs, Predicates.textMatches(text))
            ?: throw MaestroException.ElementNotFound(
                "No element with text: $text",
                viewHierarchy()
            )
    }

    fun findElementByRegexp(regex: Regex, timeoutMs: Long): UiElement {
        LOGGER.info("Looking for element by regex: ${regex.pattern} (timeout $timeoutMs)")

        return findElementWithTimeout(timeoutMs, Predicates.textMatches(regex))
            ?: throw MaestroException.ElementNotFound(
                "No element that matches regex: $regex",
                viewHierarchy()
            )
    }

    fun viewHierarchy(): TreeNode {
        return driver.contentDescriptor()
    }

    fun findElementByIdRegex(regex: Regex, timeoutMs: Long): UiElement {
        LOGGER.info("Looking for element by id regex: ${regex.pattern} (timeout $timeoutMs)")

        return findElementWithTimeout(timeoutMs, Predicates.idMatches(regex))
            ?: throw MaestroException.ElementNotFound(
                "No element has id that matches regex $regex",
                viewHierarchy()
            )
    }

    fun findElementBySize(width: Int?, height: Int?, tolerance: Int?, timeoutMs: Long): UiElement? {
        LOGGER.info("Looking for element by size: $width x $height (tolerance $tolerance) (timeout $timeoutMs)")

        return findElementWithTimeout(timeoutMs, Predicates.sizeMatches(width, height, tolerance))
    }

    fun findElementWithTimeout(
        timeoutMs: Long,
        predicate: ElementLookupPredicate,
    ): UiElement? {
        return MaestroTimer.withTimeout(timeoutMs) {
            val rootNode = driver.contentDescriptor()

            findElementByPredicate(rootNode, predicate)
        }?.toUiElement()
    }

    private fun findElementByPredicate(root: TreeNode, predicate: ElementLookupPredicate): TreeNode? {
        if (predicate(root)) {
            return root
        }

        root.children.forEach { node ->
            findElementByPredicate(node, predicate)
                ?.let { return@findElementByPredicate it }
        }

        return null
    }

    fun allElementsMatching(predicate: ElementLookupPredicate): List<TreeNode> {
        return allElementsMatching(
            driver.contentDescriptor(),
            predicate
        )
    }

    private fun allElementsMatching(node: TreeNode, predicate: ElementLookupPredicate): List<TreeNode> {
        val result = mutableListOf<TreeNode>()

        if (predicate(node)) {
            result += node
        }

        node.children.forEach { child ->
            result += allElementsMatching(child, predicate)
        }

        return result
    }

    private fun waitForAppToSettle() {
        // Time buffer for any visual effects and transitions that might occur between actions.
        MaestroTimer.sleep(MaestroTimer.Reason.BUFFER, 1000)

        val hierarchyBefore = viewHierarchy()
        repeat(10) {
            val hierarchyAfter = viewHierarchy()
            if (hierarchyBefore == hierarchyAfter) {
                return
            }
            MaestroTimer.sleep(MaestroTimer.Reason.WAIT_TO_SETTLE, 200)
        }
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

    companion object {

        private val LOGGER = LoggerFactory.getLogger(Maestro::class.java)

        fun ios(host: String, port: Int): Maestro {
            val channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build()

            return ios(channel)
        }

        fun ios(channel: ManagedChannel): Maestro {
            val driver = IOSDriver(IdbIOSDevice(channel))
            driver.open()
            return Maestro(driver)
        }

        fun android(dadb: Dadb, hostPort: Int = 7001): Maestro {
            val driver = AndroidDriver(dadb, hostPort = hostPort)
            driver.open()
            return Maestro(driver)
        }
    }
}
