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
import com.github.michaelbull.result.getOrThrow
import com.github.michaelbull.result.onSuccess
import hierarchy.AXElement
import ios.IOSDevice
import ios.IOSDeviceErrors
import maestro.*
import maestro.MaestroDriverStartupException.*
import maestro.UiElement.Companion.toUiElement
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.utils.*
import okio.Sink
import okio.source
import org.slf4j.LoggerFactory
import util.XCRunnerCLIUtils
import java.io.File
import java.util.UUID
import kotlin.collections.set

class IOSDriver(
    private val iosDevice: IOSDevice,
) : Driver {

    private val deviceInfo by lazy {
        iosDevice.deviceInfo()
    }

    private val widthPoints by lazy {
        deviceInfo.widthPoints
    }
    private val heightPoints by lazy {
        deviceInfo.heightPoints
    }

    private var appId: String? = null
    private var proxySet = false

    override fun name(): String {
        return NAME
    }

    override fun open() {
        awaitLaunch()
    }

    override fun close() {
        if (proxySet) {
            resetProxy()
        }
        iosDevice.close()
        appId = null
    }

    override fun deviceInfo(): DeviceInfo {
        return runDeviceCall {
            DeviceInfo(
                platform = Platform.IOS,
                widthPixels = deviceInfo.widthPixels,
                heightPixels = deviceInfo.heightPixels,
                widthGrid = deviceInfo.widthPoints,
                heightGrid = deviceInfo.heightPoints,
            )
        }
    }

    override fun launchApp(
        appId: String,
        launchArguments: Map<String, Any>,
        sessionId: UUID?,
    ) {
        iosDevice.launch(appId, launchArguments, sessionId)
            .onSuccess { this.appId = appId }
            .getOrThrow {
                MaestroException.UnableToLaunchApp("Unable to launch app $appId ${it.message}")
            }
    }

    override fun stopApp(appId: String) {
        iosDevice.stop(appId)
    }

    override fun killApp(appId: String) {
        // On iOS there is no Process Death like on Android so this command will be a synonym to the stop command
        stopApp(appId)
    }

    override fun clearAppState(appId: String) {
        iosDevice.clearAppState(appId)
    }

    override fun clearKeychain() {
        iosDevice.clearKeychain().expect {}
    }

    override fun tap(point: Point) {
        runDeviceCall { iosDevice.tap(point.x, point.y) }
    }

    override fun longPress(point: Point) {
        runDeviceCall { iosDevice.longPress(point.x, point.y, 3000) }
    }

    override fun pressKey(code: KeyCode) {
        val keyCodeNameMap = mapOf(
            KeyCode.BACKSPACE to "delete",
            KeyCode.ENTER to "return",
        )

        val buttonNameMap = mapOf(
            KeyCode.HOME to "home",
            KeyCode.LOCK to "lock",
        )

        runDeviceCall {
            keyCodeNameMap[code]?.let { name ->
                iosDevice.pressKey(name)
            }

            buttonNameMap[code]?.let { name ->
                iosDevice.pressButton(name)
            }
        }
    }

    override fun contentDescriptor(excludeKeyboardElements: Boolean): TreeNode {
        return runDeviceCall { viewHierarchy(excludeKeyboardElements) }
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
            Insights.report(Insight(message, Insight.Level.INFO))
        } else {
            Insights.report(Insight("", Insight.Level.NONE))
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
        swipe(
            start = Point(widthPercentToPoint(0.5), heightPercentToPoint(0.5)),
            end = Point(widthPercentToPoint(0.5), heightPercentToPoint(0.1)),
            durationMs = 333,
        )
    }

    override fun isKeyboardVisible(): Boolean {
        return runDeviceCall { iosDevice.isKeyboardVisible() }
    }

    private fun validate(start: Point, end: Point): Pair<Point, Point> {
        val screenWidth = widthPoints
        val screenHeight = heightPoints

        val validatedStart = Point(
            x = start.x.coerceIn(0, screenWidth),
            y = start.y.coerceIn(0, screenHeight)
        )

        val validatedEnd = Point(
            x = end.x.coerceIn(0, screenWidth),
            y = end.y.coerceIn(0, screenHeight)
        )

        return Pair(validatedStart, validatedEnd)
    }

    override fun swipe(
        start: Point,
        end: Point,
        durationMs: Long
    ) {
        val validatedPoints = validate(start, end)

        val startPoint = validatedPoints.first
        val endPoint = validatedPoints.second

        runDeviceCall {
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

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        val startPoint: Point
        val endPoint: Point

        when (swipeDirection) {
            SwipeDirection.UP -> {
                startPoint = Point(
                    x = widthPercentToPoint(0.5),
                    y = heightPercentToPoint(0.9),
                )
                endPoint = Point(
                    x = widthPercentToPoint(0.5),
                    y = heightPercentToPoint(0.1),
                )
            }
            SwipeDirection.DOWN -> {
                startPoint = Point(
                    x = widthPercentToPoint(0.5),
                    y = heightPercentToPoint(0.2),
                )
                endPoint = Point(
                    x = widthPercentToPoint(0.5),
                    y = heightPercentToPoint(0.9),
                )
            }
            SwipeDirection.RIGHT -> {
                startPoint = Point(
                    x = widthPercentToPoint(0.1),
                    y = heightPercentToPoint(0.5),
                )
                endPoint = Point(
                    x = widthPercentToPoint(0.9),
                    y = heightPercentToPoint(0.5),
                )
            }
            SwipeDirection.LEFT -> {
                startPoint = Point(
                    x = widthPercentToPoint(0.9),
                    y = heightPercentToPoint(0.5),
                )
                endPoint = Point(
                    x = widthPercentToPoint(0.1),
                    y = heightPercentToPoint(0.5),
                )
            }
        }
        swipe(startPoint, endPoint, durationMs)
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        when (direction) {
            SwipeDirection.UP -> {
                val end = Point(x = elementPoint.x, y = heightPercentToPoint(0.1))
                swipe(elementPoint, end, durationMs)
            }
            SwipeDirection.DOWN -> {
                val end = Point(x = elementPoint.x, y = heightPercentToPoint(0.9))
                swipe(elementPoint, end, durationMs)
            }
            SwipeDirection.RIGHT -> {
                val end = Point(x = widthPercentToPoint(0.9), y = elementPoint.y)
                swipe(elementPoint, end, durationMs)
            }
            SwipeDirection.LEFT -> {
                val end = Point(x = widthPercentToPoint(0.1), y = elementPoint.y)
                swipe(elementPoint, end, durationMs)
            }
        }
    }

    override fun backPress() {}

    override fun hideKeyboard() {
        dismissKeyboardIntroduction()

        if (isKeyboardHidden()) return

        swipe(
            start = Point(widthPercentToPoint(0.5), heightPercentToPoint(0.5)),
            end = Point(widthPercentToPoint(0.5), heightPercentToPoint(0.47)),
            durationMs = 50,
        )

        if (isKeyboardHidden()) return

        swipe(
            start = Point(widthPercentToPoint(0.5), heightPercentToPoint(0.5)),
            end = Point(widthPercentToPoint(0.47), heightPercentToPoint(0.5)),
            durationMs = 50,
        )

        waitForAppToSettle(null, null)
    }

    private fun isKeyboardHidden(): Boolean {
        val filter = Filters.idMatches("delete".toRegex())
        val element = MaestroTimer.withTimeout(2000) {
            filter(contentDescriptor().aggregate()).firstOrNull()
        }?.toUiElementOrNull()

        return element == null
    }

    private fun dismissKeyboardIntroduction() {
        val fastTypingInstruction = "Speed up your typing by sliding your finger across the letters to compose a word.*".toRegex()
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
        runDeviceCall { iosDevice.takeScreenshot(out, compressed) }
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        val iosScreenRecording = iosDevice.startScreenRecording(out).expect {}
        return object : ScreenRecording {
            override fun close() = iosScreenRecording.close()
        }
    }

    override fun inputText(text: String) {
        // silently fail if no XCUIElement has focus
        runDeviceCall { iosDevice.input(text = text) }
    }

    override fun openLink(link: String, appId: String?, autoVerify: Boolean, browser: Boolean) {
        iosDevice.openLink(link).expect {}
    }

    override fun setLocation(latitude: Double, longitude: Double) {
        iosDevice.setLocation(latitude, longitude).expect {}
    }

    override fun eraseText(charactersToErase: Int) {
        runDeviceCall { iosDevice.eraseText(charactersToErase) }
    }

    override fun setProxy(host: String, port: Int) {
        XCRunnerCLIUtils.setProxy(host, port)
        proxySet = true
    }

    override fun resetProxy() {
        XCRunnerCLIUtils.resetProxy()
    }

    override fun isShutdown(): Boolean {
        return iosDevice.isShutdown()
    }

    override fun waitUntilScreenIsStatic(timeoutMs: Long): Boolean {
        return MaestroTimer.retryUntilTrue(timeoutMs) {
            val isScreenStatic = isScreenStatic()

            LOGGER.info("screen static = $isScreenStatic")
            return@retryUntilTrue isScreenStatic
        }
    }

    override fun waitForAppToSettle(initialHierarchy: ViewHierarchy?, appId: String?, timeoutMs: Int?): ViewHierarchy? {
        LOGGER.info("Waiting for animation to end with timeout $SCREEN_SETTLE_TIMEOUT_MS")
        val didFinishOnTime = waitUntilScreenIsStatic(SCREEN_SETTLE_TIMEOUT_MS)

        return if (didFinishOnTime) null else ScreenshotUtils.waitForAppToSettle(initialHierarchy, this, timeoutMs)
    }

    override fun capabilities(): List<Capability> {
        return emptyList()
    }

    override fun setPermissions(appId: String, permissions: Map<String, String>) {
        runDeviceCall {
            iosDevice.setPermissions(appId, permissions)
        }
    }

    override fun addMedia(mediaFiles: List<File>) {
        LOGGER.info("[Start] Adding media files")
        mediaFiles.forEach { addMediaToDevice(it) }
        LOGGER.info("[Done] Adding media files")
    }

    override fun isAirplaneModeEnabled(): Boolean {
        LOGGER.warn("Airplane mode is not available on iOS simulators")
        return false
    }

    override fun setAirplaneMode(enabled: Boolean) {
        LOGGER.warn("Airplane mode is not available on iOS simulators")
    }

    private fun addMediaToDevice(mediaFile: File) {
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

    private fun isScreenStatic(): Boolean {
        return runDeviceCall { iosDevice.isScreenStatic() }
    }

    private fun heightPercentToPoint(percent: Double): Int {
        return (percent * heightPoints).toInt()
    }

    private fun widthPercentToPoint(percent: Double): Int {
        return (percent * widthPoints).toInt()
    }

    private fun awaitLaunch() {
        val startTime = System.currentTimeMillis()

        while (System.currentTimeMillis() - startTime < getStartupTimeout()) {
            runCatching {
                iosDevice.open()
                return
            }
            Thread.sleep(100)
        }

        throw IOSDriverTimeoutException("Maestro iOS driver did not start up in time")
    }

    private fun <T> runDeviceCall(call: () -> T): T {
        return try {
            call()
        } catch (appCrashException: IOSDeviceErrors.AppCrash) {
            throw MaestroException.AppCrash(appCrashException.errorMessage)
        }
    }

    private fun getStartupTimeout(): Long = runCatching {
        System.getenv(MAESTRO_DRIVER_STARTUP_TIMEOUT).toLong()
    }.getOrDefault(SERVER_LAUNCH_TIMEOUT_MS)

    companion object {
        const val NAME = "iOS Simulator"

        private val LOGGER = LoggerFactory.getLogger(IOSDevice::class.java)

        private const val SERVER_LAUNCH_TIMEOUT_MS = 15000L
        private const val MAESTRO_DRIVER_STARTUP_TIMEOUT = "MAESTRO_DRIVER_STARTUP_TIMEOUT"

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
