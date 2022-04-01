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

    override fun open() {}

    override fun close() {
        iosDevice.clearChannel()
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

    override fun scrollVertical() {}

    override fun backPress() {}
}
