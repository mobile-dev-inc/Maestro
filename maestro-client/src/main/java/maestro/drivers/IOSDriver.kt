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
import ios.IOSDevice
import maestro.DeviceInfo
import maestro.Driver
import maestro.MaestroException
import maestro.Point
import maestro.TreeNode
import maestro.utils.FileUtils
import java.io.File
import java.nio.file.Files
import kotlin.collections.set

class IOSDriver(
    private val iosDevice: IOSDevice,
) : Driver {

    private var widthPixels: Int? = null
    private var heightPixels: Int? = null

    override fun name(): String {
        return "iOS Simulator"
    }

    override fun open() {
        val response = iosDevice.deviceInfo().expect {}

        widthPixels = response.widthPixels
        heightPixels = response.heightPixels
    }

    override fun close() {
        iosDevice.close()

        widthPixels = null
        heightPixels = null
    }

    override fun deviceInfo(): DeviceInfo {
        val response = iosDevice.deviceInfo().expect {}

        return DeviceInfo(
            widthPixels = response.widthPixels,
            heightPixels = response.heightPixels
        )
    }

    override fun launchApp(appId: String) {
        iosDevice.stop(appId)
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

    override fun contentDescriptor(): TreeNode {
        val accessibilityNodes = iosDevice.contentDescriptor().expect {}

        return TreeNode(
            children = accessibilityNodes.map { node ->
                val attributes = mutableMapOf<String, String>()

                (node.title ?: node.axLabel)?.let {
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
                    attributes = attributes
                )
            }
        )
    }

    override fun scrollVertical() {
        val screenWidth = widthPixels ?: throw IllegalStateException("Screen width not available")
        val screenHeight = heightPixels ?: throw IllegalStateException("Screen height not available")

        iosDevice.scroll(
            xStart = screenWidth / 2,
            yStart = screenHeight / 4,
            xEnd = screenWidth / 2,
            yEnd = 0
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

    override fun swipe(start: Point, end: Point) {
        validate(start, end)

        iosDevice.scroll(
            xStart = start.x,
            yStart = start.y,
            xEnd = end.x,
            yEnd = end.y
        ).expect {}
    }

    override fun backPress() {}

    override fun inputText(text: String) {
        iosDevice.input(text).expect {}
    }

    override fun openLink(link: String) {
        iosDevice.openLink(link).expect {}
    }
}
