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
import com.github.michaelbull.result.getOr
import com.github.michaelbull.result.getOrThrow
import ios.IOSDevice
import ios.idb.IdbIOSDevice
import maestro.DeviceInfo
import maestro.Driver
import maestro.KeyCode
import maestro.MaestroException
import maestro.Point
import maestro.ScreenRecording
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.utils.FileUtils
import okio.Sink
import java.io.File
import java.nio.file.Files
import java.security.Key
import kotlin.collections.set

class IOSDriver(
    private val iosDevice: IOSDevice,
) : Driver {

    private var widthPixels: Int? = null
    private var heightPixels: Int? = null

    private var proxySet = false

    override fun name(): String {
        return "iOS Simulator"
    }

    override fun open() {
        val response = iosDevice.deviceInfo().expect {}

        widthPixels = response.widthPixels
        heightPixels = response.heightPixels
    }

    override fun close() {
        if (proxySet) {
            resetProxy()
        }
        iosDevice.close()

        widthPixels = null
        heightPixels = null
    }

    override fun deviceInfo(): DeviceInfo {
        val response = iosDevice.deviceInfo().expect {}

        return DeviceInfo(
            widthPixels = response.widthPixels,
            heightPixels = response.heightPixels,
            widthGrid = response.widthPoints,
            heightGrid = response.heightPoints,
        )
    }

    override fun launchApp(appId: String) {
        iosDevice.launch(appId)
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
        val accessibilityNodes = iosDevice.contentDescriptor().expect {}

        // This is a workaround for an idb issue that doesn't return children of a Tab Bar view
        // We are locating such a view and then scan it for children by probing individual points on the screen
        val groupChildren = accessibilityNodes
            .filter { it.type == "Group" }
            .filter { (it.frame?.width ?: 0F) > 50F }
            .maxByOrNull { it.frame?.y ?: 0F }
            ?.let { node ->
                node.frame
                    ?.let { frame ->
                        (frame.x.toInt()..(frame.x + frame.width).toInt())
                            .step((frame.width / 5).toInt())
                            .flatMap { x ->
                                iosDevice
                                    .describePoint(x, (frame.y + frame.height / 2).toInt())
                                    .getOr(emptyList())
                            }
                    }
            }
            ?: emptyList()

        val allNodes = accessibilityNodes + groupChildren

        return TreeNode(
            children = allNodes.map { node ->
                val attributes = mutableMapOf<String, String>()

                (node.title
                    ?: node.axLabel
                    ?: node.axValue
                    )?.let {
                        attributes["text"] = it
                    }

                (node.axUniqueId)?.let {
                    attributes["resource-id"] = it
                }

                node.frame?.let {
                    val left = it.x.toInt()
                    val top = it.y.toInt()
                    val right = left + it.width.toInt()
                    val bottom = top + it.height.toInt()

                    attributes["bounds"] = "[$left,$top][$right,$bottom]"
                }

                TreeNode(
                    attributes = attributes,
                    enabled = node.enabled,
                )
            }
        )
    }

    override fun scrollVertical() {
        val screenWidth = widthPixels ?: throw IllegalStateException("Screen width not available")
        val screenHeight = heightPixels ?: throw IllegalStateException("Screen height not available")

        iosDevice.scroll(
            xStart = screenWidth / 4,
            yStart = screenHeight / 4,
            xEnd = screenWidth / 4,
            yEnd = 0,
            durationMs = IdbIOSDevice.DEFAULT_SWIPE_DURATION_MILLIS,
            scrollType = IdbIOSDevice.ScrollType.SCROLL
        ).expect {}
    }

    private fun validate(start: Point, end: Point) {
        val screenWidth = widthPixels ?: throw IllegalStateException("Screen width not available")
        val screenHeight = heightPixels ?: throw IllegalStateException("Screen height not available")

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

    override fun swipe(start: Point, end: Point, durationMs: Long) {
        validate(start, end)

        iosDevice.scroll(
            xStart = start.x,
            yStart = start.y,
            xEnd = end.x,
            yEnd = end.y,
            durationMs = durationMs,
            scrollType = IdbIOSDevice.ScrollType.SWIPE
        ).expect {}
    }

    override fun swipe(swipeDirection: SwipeDirection, durationMs: Long) {
        val width = widthPixels ?: throw IllegalStateException("Device width not available")
        val height = heightPixels ?: throw IllegalStateException("Device height not available")

        when (swipeDirection) {
            SwipeDirection.UP -> {
                iosDevice.scroll(
                    xStart = width / 4,
                    yStart = height,
                    xEnd = width / 4,
                    yEnd = height / 4,
                    durationMs = durationMs,
                    IdbIOSDevice.ScrollType.SWIPE
                ).expect {}
            }
            SwipeDirection.DOWN -> {
                iosDevice.scroll(
                    xStart = width / 4,
                    yStart = 0,
                    xEnd = width,
                    yEnd = height / 4,
                    durationMs = durationMs,
                    IdbIOSDevice.ScrollType.SWIPE
                ).expect {}
            }
            SwipeDirection.RIGHT -> {
                iosDevice.scroll(
                    xStart = 0,
                    yStart = height / 4,
                    xEnd = width / 4,
                    yEnd = height / 4,
                    durationMs = durationMs,
                    IdbIOSDevice.ScrollType.SWIPE
                ).expect {}
            }
            SwipeDirection.LEFT -> {
                iosDevice.scroll(
                    xStart = width / 4,
                    yStart = height / 4,
                    xEnd = 0,
                    yEnd = height / 4,
                    durationMs = durationMs,
                    IdbIOSDevice.ScrollType.SWIPE
                ).expect {}
            }
        }
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
        ProcessBuilder("networksetup", "-setwebproxy", "Wi-Fi", host, port.toString())
            .redirectErrorStream(true)
            .start()
            .waitFor()
        ProcessBuilder("networksetup", "-setsecurewebproxy", "Wi-Fi", host, port.toString())
            .redirectErrorStream(true)
            .start()
            .waitFor()

        proxySet = true
    }

    override fun resetProxy() {
        ProcessBuilder("networksetup", "-setwebproxystate", "Wi-Fi", "off")
            .redirectErrorStream(true)
            .start()
            .waitFor()
        ProcessBuilder("networksetup", "-setsecurewebproxystate", "Wi-Fi", "off")
            .redirectErrorStream(true)
            .start()
            .waitFor()
    }

    override fun isShutdown(): Boolean {
        return iosDevice.isShutdown()
    }
}
