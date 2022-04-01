package conductor

import conductor.drivers.AndroidDriver
import conductor.drivers.IOSDriver
import dadb.Dadb
import io.grpc.ManagedChannelBuilder
import ios.idb.IdbIOSDevice

class Conductor(private val driver: Driver) : AutoCloseable {

    fun deviceInfo(): DeviceInfo {
        return driver.deviceInfo()
    }

    fun backPress() {
        driver.backPress()
    }

    fun scrollVertical() {
        driver.scrollVertical()
    }

    fun tap(element: TreeNode) {
        tap(toUiElement(element))
    }

    fun tap(element: UiElement) {
        driver.tap(element.bounds.center())
    }

    fun findElementByText(text: String, timeoutMs: Long): UiElement {
        return findElementWithTimeout(timeoutMs) {
            it.attributes["text"]?.let { value ->
                text == value
            } ?: false
        } ?: throw NotFoundException("No element with text: $text;  Available elements: ${driver.contentDescriptor()}")
    }

    fun findElementByRegexp(regex: Regex, timeoutMs: Long): UiElement {
        return findElementWithTimeout(timeoutMs) {
            it.attributes["text"]?.let { value ->
                regex.matches(value)
            } ?: false
        }
            ?: throw NotFoundException("No element that matches regex: $regex; Available elements: ${driver.contentDescriptor()}")
    }

    fun viewHierarchy(): TreeNode {
        return driver.contentDescriptor()
    }

    fun findElementByIdRegex(regex: Regex, timeoutMs: Long): UiElement {
        return findElementWithTimeout(timeoutMs) {
            it.attributes["resource-id"]?.let { value ->
                regex.matches(value)
            } ?: false
        }
            ?: throw NotFoundException("No element has id that matches regex $regex; Available elements: ${driver.contentDescriptor()}")
    }

    private fun toUiElement(node: TreeNode): UiElement {
        // TODO needs different impl for iOS
        val boundsStr = node.attributes["bounds"]
            ?: throw IllegalStateException("Node has no bounds")

        val boundsArr = boundsStr
            .replace("][", ",")
            .removePrefix("[")
            .removeSuffix("]")
            .split(",")
            .map { it.toInt() }

        return UiElement(
            Bounds(
                x = boundsArr[0],
                y = boundsArr[1],
                width = boundsArr[2] - boundsArr[0],
                height = boundsArr[3] - boundsArr[1]
            )
        )
    }

    private fun findElementWithTimeout(
        timeoutMs: Long,
        predicate: (TreeNode) -> Boolean,
    ): UiElement? {
        val endTime = System.currentTimeMillis() + timeoutMs

        do {
            val result = findElementByPredicate(driver.contentDescriptor(), predicate)

            if (result != null) {
                return toUiElement(result)
            }
        } while (System.currentTimeMillis() < endTime)

        return null
    }

    private fun findElementByPredicate(root: TreeNode, predicate: (TreeNode) -> Boolean): TreeNode? {
        if (predicate(root)) {
            return root
        }

        root.children.forEach { node ->
            findElementByPredicate(node, predicate)
                ?.let { return@findElementByPredicate it }
        }

        return null
    }

    class NotFoundException(msg: String) : Exception(msg)

    companion object {

        fun ios(host: String, port: Int): Conductor {
            val channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build()

            return Conductor(
                IOSDriver(IdbIOSDevice(channel, host))
            )
        }

        fun android(dadb: Dadb): Conductor {
            val driver = AndroidDriver(dadb)
            driver.open()
            return Conductor(driver)
        }
    }

    override fun close() {
        driver.close()
    }
}
