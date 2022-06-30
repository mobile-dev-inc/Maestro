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

    override fun backPress() {}

    override fun inputText(text: String) {
        iosDevice.input(text).expect {}
    }
}
