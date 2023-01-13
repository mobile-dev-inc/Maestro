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

import api.GetRunningAppIdResolver
import com.github.michaelbull.result.Err
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.expect
import com.github.michaelbull.result.getOrThrow
import com.github.michaelbull.result.onSuccess
import driver.XcUITestDriver
import hierarchy.IdbElementNode
import hierarchy.XCUIElement
import hierarchy.XCUIElementNode
import ios.IOSDevice
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.MaestroException
import maestro.Platform
import maestro.Point
import maestro.PointF
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.debuglog.IOSDriverLogger
import maestro.logger.Logger
import maestro.utils.FileUtils
import okio.Sink
import util.XCRunnerSimctl
import java.io.File
import java.net.ConnectException
import java.nio.file.Files
import kotlin.collections.set

class IOSDriver(
    private val iosDevice: IOSDevice,
    private val logger: Logger = IOSDriverLogger()
) : Driver {

    private var widthPoints: Int? = null
    private var heightPoints: Int? = null
    private var appId: String? = null
    private var proxySet = false

    private val getRunningAppIdResolver by lazy { GetRunningAppIdResolver(logger) }
    private val xcUiTestDriver by lazy { XcUITestDriver(logger, iosDevice.deviceId ?: throw IllegalStateException("No device id found")) }

    override fun name(): String {
        return "iOS Simulator"
    }

    override fun open() {
        ensureGrpcChannel()
        ensureXCUITestChannel()
    }

    private fun ensureXCUITestChannel() {
        logger.info("[Start] Uninstalling xctest ui runner app on ${iosDevice.deviceId}")
        xcUiTestDriver.killAndUninstall()
        logger.info("[Done] Uninstalling xctest ui runner app on ${iosDevice.deviceId}")
        xcUiTestDriver.setup()
    }

    @SuppressWarnings("Used in cloud")
    fun ensureGrpcChannel() {
        val response = iosDevice.deviceInfo().expect {}

        widthPoints = response.widthPoints
        heightPoints = response.heightPoints
    }

    @SuppressWarnings("Used in cloud")
    fun closeGrpcChannel() {
        iosDevice.close()
        widthPoints = null
        heightPoints = null
    }

    override fun close() {
        if (proxySet) {
            resetProxy()
        }
        closeGrpcChannel()
        xcUiTestDriver.cleanup()
        appId = null
    }

    override fun deviceInfo(): DeviceInfo {
        val response = iosDevice.deviceInfo().expect {}

        return DeviceInfo(
            platform = Platform.IOS,
            widthPixels = response.widthPixels,
            heightPixels = response.heightPixels,
            widthGrid = response.widthPoints,
            heightGrid = response.heightPoints,
        )
    }

    override fun launchApp(appId: String) {
        iosDevice.launch(appId)
            .onSuccess { this.appId = appId }
            .getOrThrow {
                MaestroException.UnableToLaunchApp("Unable to launch app $appId ${it.message}")
            }
    }

    override fun stopApp(appId: String) {
        iosDevice.stop(appId)
    }

    override fun clearAppState(appId: String) {
        iosDevice.clearAppState(appId)
    }

    override fun clearKeychain() {
        iosDevice.clearKeychain().expect {}
    }

    override fun pullAppState(appId: String, outFile: File) {
        if (!outFile.exists()) outFile.createNewFile()
        val tmpDir = Files.createTempDirectory("maestro_state_")

        iosDevice.pullAppState(appId, tmpDir.toFile()).getOrThrow {
            MaestroException.UnableToPullState("Unable to pull state for $appId. ${it.message}")
        }

        FileUtils.zipDir(tmpDir, outFile.toPath())
        FileUtils.deleteDir(tmpDir)
    }

    override fun pushAppState(appId: String, stateFile: File) {
        val tmpDir = Files.createTempDirectory("maestro_state_")
        FileUtils.unzip(stateFile.toPath(), tmpDir)

        iosDevice.pushAppState(appId, tmpDir.toFile()).getOrThrow {
            MaestroException.UnableToPushState("Unable to push state for $appId. ${it.message}")
        }

        FileUtils.deleteDir(tmpDir)
    }

    override fun tap(point: Point) {
        iosDevice.tap(point.x, point.y).expect {}
    }

    override fun longPress(point: Point) {
        iosDevice.longPress(point.x, point.y).expect {}
    }

    override fun pressKey(code: KeyCode) {
        return when (code) {
            KeyCode.ENTER -> pressKey(40)  // keyboardReturnOrEnter
            KeyCode.BACKSPACE -> pressKey(42)   // keyboardDeleteOrBackspace
            KeyCode.VOLUME_UP -> pressKey(128)  // keyboardVolumeUp
            KeyCode.VOLUME_DOWN -> pressKey(129)    // keyboardVolumeDown
            KeyCode.HOME -> pressButton(1)  // idb.HIDButtonType.HOME
            KeyCode.LOCK -> pressButton(2)  // idb.HIDButtonType.LOCK
            KeyCode.BACK -> Unit // Do nothing, back key is not available on iOS
            KeyCode.REMOTE_UP -> pressKey(82)
            KeyCode.REMOTE_DOWN -> pressKey(81)
            KeyCode.REMOTE_LEFT -> pressKey(80)
            KeyCode.REMOTE_RIGHT -> pressKey(79)
            KeyCode.REMOTE_CENTER -> Unit
            KeyCode.REMOTE_PLAY_PAUSE -> Unit
            KeyCode.REMOTE_STOP -> Unit
            KeyCode.REMOTE_NEXT -> Unit
            KeyCode.REMOTE_PREVIOUS -> Unit
            KeyCode.REMOTE_REWIND -> Unit
            KeyCode.REMOTE_FAST_FORWARD -> Unit
        }
    }

    private fun pressKey(code: Int) {
        iosDevice.pressKey(code).expect {}
    }

    private fun pressButton(code: Int) {
        iosDevice.pressButton(code).expect {}
    }

    override fun contentDescriptor(): TreeNode {
        val resolvedAppId = activeAppId()

        logger.info("Getting view hierarchy for $resolvedAppId")

        return when (val contentDescriptorResult = iosDevice.contentDescriptor(requireNotNull(resolvedAppId))) {
            is Ok -> mapHierarchy(contentDescriptorResult.value)
            is Err -> TreeNode()
        }
    }

    private fun mapHierarchy(xcUiElement: XCUIElement): TreeNode {
        return when (xcUiElement) {
            is XCUIElementNode -> parseXCUIElementNode(xcUiElement)
            is IdbElementNode -> parseIdbElementNode(xcUiElement)
            else -> throw IllegalStateException("Illegal instance for parsing hierarchy")
        }
    }

    private fun parseIdbElementNode(xcUiElement: IdbElementNode) = TreeNode(
        children = xcUiElement.children.map {
            val attributes = mutableMapOf<String, String>()

            (it.title
                ?: it.axLabel
                ?: it.axValue
                )?.let { title ->
                    attributes["text"] = title
                }

            (it.axUniqueId)?.let { resourceId ->
                attributes["resource-id"] = resourceId
            }

            it.frame.let { frame ->
                val left = frame.x.toInt()
                val top = frame.y.toInt()
                val right = left + frame.width.toInt()
                val bottom = top + frame.height.toInt()

                attributes["bounds"] = "[$left,$top][$right,$bottom]"
            }

            TreeNode(
                attributes = attributes,
                enabled = it.enabled,
            )
        }
    )

    private fun parseXCUIElementNode(xcUiElement: XCUIElementNode): TreeNode {
        val attributes = mutableMapOf<String, String>()
        val text = xcUiElement.title?.ifEmpty {
            xcUiElement.label.ifEmpty {
                xcUiElement.value?.ifEmpty {
                    xcUiElement.placeholderValue
                }
            }
        }
        attributes["text"] = text ?: ""
        attributes["resource-id"] = xcUiElement.identifier
        val right = xcUiElement.frame.x + xcUiElement.frame.width
        val bottom = xcUiElement.frame.y + xcUiElement.frame.height
        attributes["bounds"] = "[${xcUiElement.frame.x.toInt()},${xcUiElement.frame.y.toInt()}][${right.toInt()},${bottom.toInt()}]"
        attributes["enabled"] = xcUiElement.enabled.toString()
        attributes["focused"] = xcUiElement.hasFocus.toString()
        attributes["selected"] = xcUiElement.selected.toString()

        val children = mutableListOf<TreeNode>()
        val childNodes = xcUiElement.children
        if (childNodes != null) {
            (0 until childNodes.size).forEach { i ->
                children += mapHierarchy(childNodes[i])
            }
        }

        return TreeNode(
            attributes = attributes,
            children = children,
            enabled = xcUiElement.enabled,
            focused = xcUiElement.hasFocus,
            selected = xcUiElement.selected
        )
    }

    override fun scrollVertical() {
        val appId = activeAppId() ?: return

        iosDevice.scroll(
            appId = appId,
            xStart = 0.5f,
            yStart = 0.9f,
            xEnd = 0.5f,
            yEnd = 0.1f,
            velocity = null
        ).expect {}
    }

    private fun validate(start: Point, end: Point) {
        val screenWidth = widthPoints ?: throw IllegalStateException("Screen width not available")
        val screenHeight = heightPoints ?: throw IllegalStateException("Screen height not available")

        if (start.x < 0 || start.x > screenWidth) {
            throw java.lang.IllegalArgumentException("x value of start point (${start.x}) needs to be between 0 and $screenWidth")
        }
        if (end.x < 0 || end.x > screenWidth) {
            throw java.lang.IllegalArgumentException("x value of end point (${end.x}) needs to be between 0 and $screenWidth")
        }

        if (start.y < 0 || start.y > screenHeight) {
            throw java.lang.IllegalArgumentException("y value of start point (${start.y}) needs to be between 0 and $screenHeight")
        }
        if (end.y < 0 || end.y > screenHeight) {
            throw java.lang.IllegalArgumentException("y value of end point (${end.y}) needs to be between 0 and $screenHeight")
        }
    }

    override fun swipe(
        start: Point,
        end: Point,
        durationMs: Long
    ) {
        validate(start, end)

        val width = widthPoints ?: throw IllegalStateException("Device width not available")
        val height = heightPoints ?: throw IllegalStateException("Device height not available")

        val normalisedStart = start.normalise(
            width,
            height,
        )
        val normalisedEnd = end.normalise(
            width,
            height,
        )

        iosDevice.scroll(
            appId = activeAppId() ?: return,
            xStart = normalisedStart.x,
            yStart = normalisedStart.y,
            xEnd = normalisedEnd.x,
            yEnd = normalisedEnd.y,
            velocity = if (durationMs > 0) {
                start.distance(end) / toSeconds(durationMs)
            } else {
                Float.MAX_VALUE
            }
        ).expect {}
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        val startPoint: PointF
        val endPoint: PointF

        when (swipeDirection) {
            SwipeDirection.UP -> {
                startPoint = PointF(
                    x = 0.5f,
                    y = 0.9f,
                )
                endPoint = PointF(
                    x = 0.5F,
                    y = 0.1f,
                )
            }
            SwipeDirection.DOWN -> {
                startPoint = PointF(
                    x = 0.5f,
                    y = 0.2f,
                )
                endPoint = PointF(
                    x = 0.5F,
                    y = 0.9f,
                )
            }
            SwipeDirection.RIGHT -> {
                startPoint = PointF(
                    x = 0.1f,
                    y = 0.5f,
                )
                endPoint = PointF(
                    x = 0.9F,
                    y = 0.5f,
                )
            }
            SwipeDirection.LEFT -> {
                startPoint = PointF(
                    x = 0.9f,
                    y = 0.5f,
                )
                endPoint = PointF(
                    x = 0.1F,
                    y = 0.5f,
                )
            }
        }
        directionalSwipe(durationMs, startPoint, endPoint)
    }

    override fun swipe(elementPoint: Point, direction: SwipeDirection, durationMs: Long) {
        val width = widthPoints ?: throw IllegalStateException("Device width not available")
        val height = heightPoints ?: throw IllegalStateException("Device height not available")

        when (direction) {
            SwipeDirection.UP -> {
                val end = PointF(x = 0.5f, y = 0.1f)
                val start = elementPoint.normalise(width, height)
                directionalSwipe(durationMs, start, end)
            }
            SwipeDirection.DOWN -> {
                val end = PointF(x = 0.5f, y = 0.9f)
                val start = elementPoint.normalise(width, height)
                directionalSwipe(durationMs, start, end)
            }
            SwipeDirection.RIGHT -> {
                val end = PointF(x = 0.9f, y = 0.5f)
                val start = elementPoint.normalise(width, height)
                directionalSwipe(durationMs, start, end)
            }
            SwipeDirection.LEFT -> {
                val end = PointF(x = 0.1f, y = 0.5f)
                val start = elementPoint.normalise(width, height)
                directionalSwipe(durationMs, start, end)
            }
        }
    }

    private fun directionalSwipe(durationMs: Long, start: PointF, end: PointF) {
        val width = widthPoints ?: throw IllegalStateException("Device width not available")
        val height = heightPoints ?: throw IllegalStateException("Device height not available")

        val denormalizedDistance = PointF(start.x * width, start.y * height)
            .distance(PointF(end.x * width, end.y * height))

        iosDevice.scroll(
            appId = activeAppId() ?: return,
            xStart = start.x,
            yStart = start.y,
            xEnd = end.x,
            yEnd = end.y,
            velocity = if (durationMs > 0) {
                denormalizedDistance / toSeconds(durationMs)
            } else {
                Float.MAX_VALUE
            }
        ).expect {}
    }

    override fun backPress() {}

    override fun hideKeyboard() {
        iosDevice.pressKey(40).expect {}
    }

    override fun takeScreenshot(out: Sink) {
        iosDevice.takeScreenshot(out).expect {}
    }

    override fun startScreenRecording(out: Sink): ScreenRecording {
        val iosScreenRecording = iosDevice.startScreenRecording(out).expect {}
        return object : ScreenRecording {
            override fun close() = iosScreenRecording.close()
        }
    }

    override fun inputText(text: String) {
        iosDevice.input(text).expect {}
    }

    override fun openLink(link: String) {
        iosDevice.openLink(link).expect {}
    }

    override fun setLocation(latitude: Double, longitude: Double) {
        iosDevice.setLocation(latitude, longitude).expect {}
    }

    override fun eraseText(charactersToErase: Int) {
        repeat(charactersToErase) {
            pressKey(KeyCode.BACKSPACE)
        }
    }

    override fun setProxy(host: String, port: Int) {
        XCRunnerSimctl.setProxy(host, port)
        proxySet = true
    }

    override fun resetProxy() {
        XCRunnerSimctl.resetProxy()
    }

    override fun isShutdown(): Boolean {
        return iosDevice.isShutdown()
    }

    private fun activeAppId(): String? {
        var resolvedAppId: String? = ""
        for (i in 0..MAX_RETRIES) {
            val resolvedApp = try {
                resolvedAppId = getRunningAppIdResolver.invoke() ?: appId
                resolvedAppId
            } catch (connectException: ConnectException) {
                xcUiTestDriver.setup()
                null
            }
            if (!resolvedApp.isNullOrEmpty()) break
        }
        return resolvedAppId
    }

    private fun toSeconds(ms: Long): Float {
        return ms / 1000f
    }

    companion object {
        private const val MAX_RETRIES = 3
    }
}
