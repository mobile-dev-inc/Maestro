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

package maestro.drivers

import com.github.michaelbull.result.expect
import device.IOSDevice
import hierarchy.AXElement
import ios.IOSDeviceErrors
import maestro.Capability
import maestro.DeviceInfo
import maestro.DeviceOrientation
import maestro.Driver
import maestro.Filters
import maestro.KeyCode
import maestro.MaestroException
import maestro.MediaExt
import maestro.NamedSource
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.UiElement.Companion.toUiElement
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.ViewHierarchy
import maestro.toCommonDeviceInfo
import maestro.utils.Insight
import maestro.utils.Insights
import maestro.utils.MaestroTimer
import maestro.utils.Metrics
import maestro.utils.MetricsProvider
import maestro.utils.NoopInsights
import maestro.utils.ScreenshotUtils
import maestro.utils.TempFileHandler
import okio.Sink
import okio.source
import org.slf4j.LoggerFactory
import util.XCRunnerCLIUtils
import java.io.File
import java.net.SocketTimeoutException
import kotlin.collections.set

class IOSDriver(
    private val iosDevice: IOSDevice,
    private val insights: Insights = NoopInsights,
    private val metricsProvider: Metrics = MetricsProvider.getInstance(),
) : Driver {

    private val metrics = metricsProvider.withPrefix("maestro.driver").withTags(mapOf("platform" to "ios", "deviceId" to iosDevice.deviceId).filterValues { it != null }.mapValues { it.value!! })

    private var appId: String? = null
    private var proxySet = false
    private val xcRunnerCLIUtils = XCRunnerCLIUtils(tempFileHandler = TempFileHandler())

    override fun name(): String {
        return metrics.measured("name") {
            NAME
        }
    }

    override fun open() {
        metrics.measured("open") {
            iosDevice.open()
        }
    }

    override fun close() {
        metrics.measured("close") {
            if (proxySet) {
                resetProxy()
            }
            iosDevice.close()
            appId = null
        }
    }

    override fun deviceInfo(): DeviceInfo {
        return metrics.measured("operation", mapOf("command" to "deviceInfo")) {
            runDeviceCall("deviceInfo") { iosDevice.deviceInfo().toCommonDeviceInfo() }
        }
    }

    override fun launchApp(
        appId: String,
        launchArguments: Map<String, Any>,
    ) {
        metrics.measured("operation", mapOf("command" to "launchApp", "appId" to appId)) {
            iosDevice.launch(appId, launchArguments)
            this.appId = appId
        }
    }

    override fun stopApp(appId: String) {
        metrics.measured("operation", mapOf("command" to "stopApp", "appId" to appId)) {
            iosDevice.stop(appId)
        }
    }

    override fun killApp(appId: String) {
        metrics.measured("operation", mapOf("command" to "killApp", "appId" to appId)) {
            // On iOS there is no Process Death like on Android so this command will be a synonym to the stop command
            stopApp(appId)
        }
    }

    override fun clearAppState(appId: String) {
        metrics.measured("operation", mapOf("command" to "clearAppState", "appId" to appId)) {
            iosDevice.clearAppState(appId)
        }
    }

    override fun clearKeychain() {
        metrics.measured("operation", mapOf("command" to "clearKeychain")) {
            iosDevice.clearKeychain().expect {}
        }
    }

    override fun tap(point: Point) {
        metrics.measured("operation", mapOf("command" to "tap")) {
            runDeviceCall("tap") { iosDevice.tap(point.x, point.y) }
        }
    }

    override fun longPress(point: Point) {
        metrics.measured("operation", mapOf("command" to "longPress")) {
            runDeviceCall("longPress") { iosDevice.longPress(point.x, point.y, 3000) }
        }
    }

    override fun pressKey(code: KeyCode) {
        metrics.measured("operation", mapOf("command" to "pressKey")) {
            val keyCodeNameMap = mapOf(
                KeyCode.BACKSPACE to "delete",
                KeyCode.ENTER to "return",
            )

            val buttonNameMap = mapOf(
                KeyCode.HOME to "home",
                KeyCode.LOCK to "lock",
            )

            runDeviceCall("pressKey") {
                keyCodeNameMap[code]?.let { name ->
                    iosDevice.pressKey(name)
                }

                buttonNameMap[code]?.let { name ->
                    iosDevice.pressButton(name)
                }
            }
        }
    }

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
        return metrics.measured("operation", mapOf("command" to "contentDescriptor")) {
            runDeviceCall("snapshot") { viewHierarchy(excludeKeyboardElements) }
        }
    }

    private fun viewHierarchy(excludeKeyboardElements: Boolean): TreeNode {
        LOGGER.info("Requesting view hierarchy of the screen")
        val hierarchyResult = iosDevice.viewHierarchy(excludeKeyboardElements)
        LOGGER.info("Depth of the screen is ${hierarchyResult.depth}")
        if (hierarchyResult.depth > WARNING_MAX_DEPTH) {
            val message = "The view hierarchy has been calculated. The current depth of the hierarchy " +
                    "is ${hierarchyResult.depth}. This might affect the execution time of your test. " +
                    "If you are using React native, consider migrating to the new " +
                    "architecture where view flattening is available. For more information on the " +
                    "migration process, please visit: https://reactnative.dev/docs/new-architecture-intro"
            insights.report(Insight(message, Insight.Level.INFO))
        } else {
            insights.report(Insight("", Insight.Level.NONE))
        }
        val hierarchy = hierarchyResult.axElement
        return mapViewHierarchy(hierarchy)
    }

    private fun mapViewHierarchy(element: AXElement): TreeNode {
        val attributes = mutableMapOf<String, String>()
        attributes["accessibilityText"] = element.label
        attributes["title"] = element.title ?: ""
        attributes["value"] = element.value ?: ""
        attributes["text"] = element.title?.ifEmpty { element.value } ?: ""
        attributes["hintText"] = element.placeholderValue ?: ""
        attributes["resource-id"] = element.identifier
        attributes["bounds"] = element.frame.boundsString
        attributes["enabled"] = element.enabled.toString()
        attributes["focused"] = element.hasFocus.toString()
        attributes["selected"] = element.selected.toString()

        val checked = element.elementType in CHECKABLE_ELEMENTS && element.value == "1"
        attributes["checked"] = checked.toString()

        val children = element.children.map {
            mapViewHierarchy(it)
        }

        return TreeNode(
            attributes = attributes,
            children = children,
            enabled = element.enabled,
            focused = element.hasFocus,
            selected = element.selected,
            checked = checked,
        )
    }

    override fun isUnicodeInputSupported(): Boolean {
        return true
    }

    override fun scrollVertical() {
        val deviceInfo = deviceInfo()
        val width = deviceInfo.widthGrid
        val height = deviceInfo.heightGrid

        swipe(
            start = Point(0.5.asPercentOf(width), 0.5.asPercentOf(height)),
            end = Point(0.5.asPercentOf(width), 0.1.asPercentOf(height)),
            durationMs = 333,
        )
    }

    override fun isKeyboardVisible(): Boolean {
        return metrics.measured("operation", mapOf("command" to "isKeyboardVisible")) {
            runDeviceCall("isKeyboardVisible") { iosDevice.isKeyboardVisible() }
        }
    }

    override fun swipe(
        start: Point,
        end: Point,
        durationMs: Long
    ) {
        metrics.measured("operation", mapOf("command" to "swipe", "durationMs" to durationMs.toString())) {
            val deviceInfo = deviceInfo()
            val startPoint = start.coerceIn(maxWidth = deviceInfo.widthGrid, maxHeight = deviceInfo.heightGrid)
            val endPoint = end.coerceIn(maxWidth = deviceInfo.widthGrid, maxHeight = deviceInfo.heightGrid)

            runDeviceCall("swipe") {
                waitForAppToSettle(null, null)
                iosDevice.scroll(
                    xStart = startPoint.x.toDouble(),
                    yStart = startPoint.y.toDouble(),
                    xEnd = endPoint.x.toDouble(),
                    yEnd = endPoint.y.toDouble(),
                    duration = durationMs.toDouble() / 1000
                )
            }
        }
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        metrics.measured("operation", mapOf("command" to "swipeWithDirection", "direction" to swipeDirection.name, "durationMs" to durationMs.toString())) {
            val deviceInfo = deviceInfo()
            val width = deviceInfo.widthGrid
            val height = deviceInfo.heightGrid

            val startPoint: Point
            val endPoint: Point

            when (swipeDirection) {
                SwipeDirection.UP -> {
                    startPoint = Point(
                        x = 0.5.asPercentOf(width),
                        y = 0.9.asPercentOf(height),
                    )
                    endPoint = Point(
                        x = 0.5.asPercentOf(width),
                        y = 0.1.asPercentOf(height),
                    )
                }

                SwipeDirection.DOWN -> {
                    startPoint = Point(
                        x = 0.5.asPercentOf(width),
                        y = 0.2.asPercentOf(height),
                    )
                    endPoint = Point(
                        x = 0.5.asPercentOf(width),
                        y = 0.9.asPercentOf(height),
                    )
                }

                SwipeDirection.RIGHT -> {
                    startPoint = Point(
                        x = 0.1.asPercentOf(width),
                        y = 0.5.asPercentOf(height),
                    )
                    endPoint = Point(
                        x = 0.9.asPercentOf(width),
                        y = 0.5.asPercentOf(height),
                    )
                }

                SwipeDirection.LEFT -> {
                    startPoint = Point(
                        x = 0.9.asPercentOf(width),
                        y = 0.5.asPercentOf(height),
                    )
                    endPoint = Point(
                        x = 0.1.asPercentOf(width),
                        y = 0.5.asPercentOf(height),
                    )
                }
            }
            swipe(startPoint, endPoint, durationMs)
        }
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        metrics.measured("operation", mapOf("command" to "swipeWithElementPoint", "direction" to direction.name, "durationMs" to durationMs.toString())) {
            val deviceInfo = deviceInfo()
            val width = deviceInfo.widthGrid
            val height = deviceInfo.heightGrid

            when (direction) {
                SwipeDirection.UP -> {
                    val end = Point(x = elementPoint.x, y = 0.1.asPercentOf(height))
                    swipe(elementPoint, end, durationMs)
                }

                SwipeDirection.DOWN -> {
                    val end = Point(x = elementPoint.x, y = 0.9.asPercentOf(height))
                    swipe(elementPoint, end, durationMs)
                }

                SwipeDirection.RIGHT -> {
                    val end = Point(x = (0.9).asPercentOf(width), y = elementPoint.y)
                    swipe(elementPoint, end, durationMs)
                }

                SwipeDirection.LEFT -> {
                    val end = Point(x = (0.1).asPercentOf(width), y = elementPoint.y)
                    swipe(elementPoint, end, durationMs)
                }
            }
        }
    }

    override fun backPress() {}

    override fun hideKeyboard() {
        metrics.measured("operation", mapOf("command" to "hideKeyboard")) {
            val deviceInfo = deviceInfo()
            val width = deviceInfo.widthGrid
            val height = deviceInfo.heightGrid

            dismissKeyboardIntroduction(heightPoints = deviceInfo.heightGrid)

            if (isKeyboardHidden()) return@measured

            swipe(
                start = Point(0.5.asPercentOf(width), 0.5.asPercentOf(height)),
                end = Point(0.5.asPercentOf(width), 0.47.asPercentOf(height)),
                durationMs = 50,
            )

            if (isKeyboardHidden()) return@measured

            swipe(
                start = Point(0.5.asPercentOf(width), 0.5.asPercentOf(height)),
                end = Point(0.47.asPercentOf(width), 0.5.asPercentOf(height)),
                durationMs = 50,
            )

            waitForAppToSettle(null, null)
        }
    }

    private fun isKeyboardHidden(): Boolean {
        val filter = Filters.idMatches("delete".toRegex())
        val element = MaestroTimer.withTimeout(2000) {
            filter(contentDescriptor().aggregate()).firstOrNull()
        }?.toUiElementOrNull()

        return element == null
    }

    private fun dismissKeyboardIntroduction(heightPoints: Int) {
        val fastTypingInstruction =
            "Speed up your typing by sliding your finger across the letters to compose a word.*".toRegex()
        val instructionTextFilter = Filters.textMatches(fastTypingInstruction)
        val instructionText = MaestroTimer.withTimeout(2000) {
            instructionTextFilter(contentDescriptor().aggregate()).firstOrNull()
        }?.toUiElementOrNull()
        if (instructionText != null && instructionText.bounds.center().y in heightPoints / 2..heightPoints) {
            val continueElementFilter = Filters.textMatches("Continue".toRegex())
            val continueElement = MaestroTimer.withTimeout(2000) {
                continueElementFilter(contentDescriptor().aggregate()).find {
                    it.toUiElement().bounds.center().y > instructionText.bounds.center().y
                }
            }?.toUiElementOrNull()
            if (continueElement != null && continueElement.bounds.center().y > instructionText.bounds.center().y) {
                tap(continueElement.bounds.center())
            }
        }
    }

    override fun takeScreenshot(out: Sink, compressed: Boolean) {
        metrics.measured("operation", mapOf("command" to "takeScreenshot")) {
            runDeviceCall("takeScreenshot") { iosDevice.takeScreenshot(out, compressed) }
        }
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        return metrics.measured("operation", mapOf("command" to "startScreenRecording")) {
            val iosScreenRecording = iosDevice.startScreenRecording(out).expect {}
            object : ScreenRecording {
                override fun close() = iosScreenRecording.close()
            }
        }
    }

    override fun inputText(text: String) {
        metrics.measured("operation", mapOf("command" to "inputText")) {
            runDeviceCall("inputText") { iosDevice.input(text = text) }
        }
    }

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        metrics.measured("operation", mapOf("command" to "openLink", "appId" to appId.toString(), "autoVerify" to autoVerify.toString(), "browser" to browser.toString())) {
            iosDevice.openLink(link).expect {}
        }
    }

    override fun setLocation(latitude: Double, longitude: Double) {
        metrics.measured("operation", mapOf("command" to "setLocation")) {
            runDeviceCall("setLocation") { iosDevice.setLocation(latitude, longitude).expect {} }
        }
    }

    override fun setOrientation(orientation: DeviceOrientation) {
        metrics.measured("operation", mapOf("command" to "setOrientation")) {
            runDeviceCall("setOrientation") { iosDevice.setOrientation(orientation.camelCaseName) }
        }
    }

    override fun eraseText(charactersToErase: Int) {
        metrics.measured("operation", mapOf("command" to "eraseText")) {
            runDeviceCall("eraseText") { iosDevice.eraseText(charactersToErase) }
        }
    }

    override fun setProxy(host: String, port: Int) {
        metrics.measured("operation", mapOf("command" to "setProxy")) {
            xcRunnerCLIUtils.setProxy(host, port)
            proxySet = true
        }
    }

    override fun resetProxy() {
        xcRunnerCLIUtils.resetProxy()
    }

    override fun isShutdown(): Boolean {
        return metrics.measured("operation", mapOf("command" to "isShutdown")) {
            iosDevice.isShutdown()
        }
    }

    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        return metrics.measured("operation", mapOf("command" to "waitUntilScreenIsStatic", "timeoutMs" to timeoutMs.toString())) {
             MaestroTimer.retryUntilTrue(timeoutMs) {
                val isScreenStatic = isScreenStatic()

                LOGGER.info("screen static = $isScreenStatic")
                return@retryUntilTrue isScreenStatic
            }
        }
    }

    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy? {
        return metrics.measured("operation", mapOf("command" to "waitForAppToSettle", "appId" to appId.toString(), "timeoutMs" to timeoutMs.toString())) {
            LOGGER.info("Waiting for animation to end with timeout $SCREEN_SETTLE_TIMEOUT_MS")
            val didFinishOnTime = waitUntilScreenIsStatic(SCREEN_SETTLE_TIMEOUT_MS)

            if (didFinishOnTime) null else ScreenshotUtils.waitForAppToSettle(initialHierarchy, this, timeoutMs)
        }
    }

    override fun capabilities(): List<Capability> {
        return emptyList()
    }

    override fun setPermissions(appId: String, permissions: Map<String, String>) {
        metrics.measured("operation", mapOf("command" to "setPermissions", "appId" to appId)) {
            runDeviceCall("setPermissions") {
                iosDevice.setPermissions(appId, permissions)
            }
        }
    }

    override fun addMedia(mediaFiles: List<File>) {
        metrics.measured("operation", mapOf("command" to "addMedia", "mediaFilesCount" to mediaFiles.size.toString())) {
            LOGGER.info("[Start] Adding media files")
            mediaFiles.forEach { addMediaToDevice(it) }
            LOGGER.info("[Done] Adding media files")
        }
    }

    override fun isAirplaneModeEnabled(): Boolean {
        LOGGER.warn("Airplane mode is not available on iOS simulators")
        return false
    }

    override fun setAirplaneMode(enabled: Boolean) {
        LOGGER.warn("Airplane mode is not available on iOS simulators")
    }

    private fun addMediaToDevice(mediaFile: File) {
        metrics.measured("operation", mapOf("command" to "addMediaToDevice")) {
            val namedSource = NamedSource(
                mediaFile.name,
                mediaFile.source(),
                mediaFile.extension,
                mediaFile.path
            )
            MediaExt.values().firstOrNull { mediaExt -> mediaExt.extName == namedSource.extension }
                ?: throw IllegalArgumentException(
                    "Extension .${namedSource.extension} is not yet supported for add media"
                )
            iosDevice.addMedia(namedSource.path)
        }
    }

    private fun isScreenStatic(): Boolean {
        return runDeviceCall("isScreenStatic") { iosDevice.isScreenStatic() }
    }

    private fun <T> runDeviceCall(callName: String, call: () -> T): T {
        return try {
            call()
        } catch (socketTimeoutException: SocketTimeoutException) {
            LOGGER.error("Got socket timeout processing $callName command", socketTimeoutException)
            throw socketTimeoutException
        } catch (appCrashException: IOSDeviceErrors.AppCrash) {
            LOGGER.error("Detected app crash during $callName command", appCrashException)
            throw MaestroException.AppCrash(appCrashException.errorMessage)
        } catch (timeoutException: IOSDeviceErrors.OperationTimeout) {
            val debugMessage = when {
                timeoutException.errorMessage.contains("Timed out while evaluating UI query") -> {
                    """
                        Your app screen might be too complex.
                                            
                        * This usually happens when the screen has very large view hierarchies, such as table views loading with large amount of data.
                        * Try loading fewer cells initially or implementing lazy loading to reduce the load during tests.
                    """.trimIndent()
                }
                timeoutException.errorMessage.contains("Unable to perform work on main run loop, process main thread busy") -> {
                    """
                        Your app is doing heavy work on the main/UI thread.
                        
                        * Move any heavy computation or blocking work off the main thread.
                        * This ensures the UI stays responsive and Maestro can take snapshot of the screen.
                    """.trimIndent()
                }
                else -> null
            }
            throw MaestroException.DriverTimeout(
                message = "Maestro driver timed out during $callName call with: ${timeoutException.errorMessage}",
                debugMessage = debugMessage
            )
        }
    }

    companion object {
        const val NAME = "iOS Simulator"

        private val LOGGER = LoggerFactory.getLogger(IOSDevice::class.java)

        private const val ELEMENT_TYPE_CHECKBOX = 12
        private const val ELEMENT_TYPE_SWITCH = 40
        private const val ELEMENT_TYPE_TOGGLE = 41

        private const val WARNING_MAX_DEPTH = 61

        private val CHECKABLE_ELEMENTS = setOf(
            ELEMENT_TYPE_CHECKBOX,
            ELEMENT_TYPE_SWITCH,
            ELEMENT_TYPE_TOGGLE,
        )

        private const val SCREEN_SETTLE_TIMEOUT_MS: Long = 3000
    }
}

private fun Double.asPercentOf(total: Int): Int {
    return (this * total).toInt()
}

private fun Point.coerceIn(maxWidth: Int, maxHeight: Int): Point {
    return Point(
        x = x.coerceIn(0, maxWidth),
        y = y.coerceIn(0, maxHeight),
    )
}
