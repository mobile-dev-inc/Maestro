package conductor

import conductor.UiElement.Companion.toUiElement
import conductor.drivers.AndroidDriver
import conductor.drivers.IOSDriver
import dadb.Dadb
import io.grpc.ManagedChannelBuilder
import ios.idb.IdbIOSDevice
import org.slf4j.LoggerFactory

@Suppress("unused", "MemberVisibilityCanBePrivate")
class Conductor(private val driver: Driver) : AutoCloseable {

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

    fun backPress() {
        LOGGER.info("Pressing back")

        driver.backPress()
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

    fun tap(element: UiElement, retryIfNoChange: Boolean = true) {
        LOGGER.info("Tapping on element: $element")

        val hierarchyBeforeTap = viewHierarchy()

        val retries = getNumberOfRetries(retryIfNoChange)
        repeat(retries) {
            driver.tap(element.bounds.center())
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
            tap(element, false)
        }
    }

    private fun getNumberOfRetries(retryIfNoChange: Boolean): Int {
        return if (retryIfNoChange) 3 else 1
    }

    fun findElementByText(text: String, timeoutMs: Long): UiElement {
        LOGGER.info("Looking for element by text: $text (timeout $timeoutMs)")

        return findElementWithTimeout(timeoutMs, Predicates.textMatches(text))
            ?: throw ConductorException.ElementNotFound("No element with text: $text;  Available elements: ${driver.contentDescriptor()}")
    }

    fun findElementByRegexp(regex: Regex, timeoutMs: Long): UiElement {
        LOGGER.info("Looking for element by regex: ${regex.pattern} (timeout $timeoutMs)")

        return findElementWithTimeout(timeoutMs, Predicates.textMatches(regex))
            ?: throw ConductorException.ElementNotFound("No element that matches regex: $regex; Available elements: ${driver.contentDescriptor()}")
    }

    fun viewHierarchy(): TreeNode {
        return driver.contentDescriptor()
    }

    fun findElementByIdRegex(regex: Regex, timeoutMs: Long): UiElement {
        LOGGER.info("Looking for element by id regex: ${regex.pattern} (timeout $timeoutMs)")

        return findElementWithTimeout(timeoutMs, Predicates.idMatches(regex))
            ?: throw ConductorException.ElementNotFound("No element has id that matches regex $regex; Available elements: ${driver.contentDescriptor()}")
    }

    fun findElementBySize(width: Int?, height: Int?, tolerance: Int?, timeoutMs: Long): UiElement? {
        LOGGER.info("Looking for element by size: $width x $height (tolerance $tolerance) (timeout $timeoutMs)")

        return findElementWithTimeout(timeoutMs, Predicates.sizeMatches(width, height, tolerance))
    }

    fun findElementWithTimeout(
        timeoutMs: Long,
        predicate: ElementLookupPredicate,
    ): UiElement? {
        val endTime = System.currentTimeMillis() + timeoutMs

        do {
            val result = findElementByPredicate(driver.contentDescriptor(), predicate)

            if (result != null) {
                return result.toUiElement()
            }
        } while (System.currentTimeMillis() < endTime)

        return null
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
        Thread.sleep(1000)

        val hierarchyBefore = viewHierarchy()
        repeat(10) {
            val hierarchyAfter = viewHierarchy()
            if (hierarchyBefore == hierarchyAfter) {
                return
            }
            Thread.sleep(200)
        }
    }

    fun inputText(text: String) {
        driver.inputText(text)
        waitForAppToSettle()
    }

    override fun close() {
        driver.close()
    }

    companion object {

        private val LOGGER = LoggerFactory.getLogger(Conductor::class.java)

        fun ios(host: String, port: Int): Conductor {
            val channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build()

            val driver = IOSDriver(IdbIOSDevice(channel))
            driver.open()
            return Conductor(driver)
        }

        fun android(dadb: Dadb, hostPort: Int = 7001): Conductor {
            val driver = AndroidDriver(dadb, hostPort = hostPort)
            driver.open()
            return Conductor(driver)
        }
    }
}
