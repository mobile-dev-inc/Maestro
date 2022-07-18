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

package conductor.drivers

import com.github.michaelbull.result.expect
import conductor.DeviceInfo
import conductor.Driver
import conductor.Point
import conductor.TreeNode
import ios.IOSDevice

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
            throw java.lang.IllegalArgumentException("x value of end point (${start.x}) needs to be between 0 and $screenWidth")
        }

        if (start.y < 0 || start.y > screenHeight) {
            throw java.lang.IllegalArgumentException("y value of start point (${start.y}) needs to be between 0 and $screenHeight")
        }
        if (end.y < 0 || end.y > screenHeight) {
            throw java.lang.IllegalArgumentException("x value of end point (${start.y}) needs to be between 0 and $screenHeight")
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
}
