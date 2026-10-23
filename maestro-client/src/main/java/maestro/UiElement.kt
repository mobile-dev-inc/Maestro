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

package maestro

data class UiElement(
    val treeNode: TreeNode,
    val bounds: Bounds,
) {

    val clipBounds: Bounds?
        get() = treeNode.attributes[CLIP_BOUNDS_ATTRIBUTE]
            ?.let { parseBounds(it) }
            ?.takeIf { it.width > 0 && it.height > 0 }

    // Part of the bounds inside every clipping ancestor, null when none of it is.
    val visibleBounds: Bounds?
        get() {
            if (!treeNode.attributes.containsKey(CLIP_BOUNDS_ATTRIBUTE)) return bounds
            return clipBounds?.let { bounds.intersect(it) }
        }

    fun distanceTo(other: UiElement): Float {
        return bounds.center().distance(other.bounds.center())
    }

    fun getVisiblePercentage(screenWidth: Int, screenHeight: Int): Double {
        if (treeNode.attributes.containsKey(CLIP_BOUNDS_ATTRIBUTE)) {
            return bounds.visibleFraction(clipBounds)
        }

        return bounds.visibleFraction(Bounds(x = 0, y = 0, width = screenWidth, height = screenHeight))
    }

    fun isElementNearScreenCenter(direction: SwipeDirection, screenWidth: Int, screenHeight: Int): Boolean {
        val centerX = screenWidth / 2
        val centerY = screenHeight / 2

        val elementCenterX = bounds.x + (bounds.width / 2)
        val elementCenterY = bounds.y + (bounds.height / 2)

        val margin = when(direction) {
            SwipeDirection.DOWN, SwipeDirection.UP -> screenHeight / 5
            SwipeDirection.LEFT, SwipeDirection.RIGHT -> screenWidth / 5
        }

        // return true when the element center is within the <direction> half of the screen bounds plus margin
        return when(direction) {
            SwipeDirection.RIGHT -> elementCenterX > centerX - margin
            SwipeDirection.LEFT -> elementCenterX < centerX + margin
            SwipeDirection.UP -> elementCenterY < centerY + margin
            SwipeDirection.DOWN -> elementCenterY > centerY - margin
        }
    }

    fun isWithinViewPortBounds(info: DeviceInfo, paddingHorizontal: Float = 0f, paddingVertical: Float = 0f): Boolean {
        val paddingX = (info.widthGrid * paddingHorizontal).toInt()
        val paddingY = (info.heightGrid * paddingVertical).toInt()
        val xEnd = info.widthGrid - paddingX
        val yEnd = info.heightGrid - paddingY

        val isXWithinBounds = bounds.x in paddingX..xEnd
        val isYWithinBounds = bounds.y in paddingY..yEnd

        return isXWithinBounds && isYWithinBounds
    }

    companion object {

        fun TreeNode.toUiElement(): UiElement {
            return toUiElementOrNull()
                ?: throw IllegalStateException("Node has no bounds")
        }

        fun TreeNode.toUiElementOrNull(): UiElement? {
            // TODO needs different impl for iOS
            val boundsStr = attributes["bounds"]
                ?: return null

            return UiElement(this, parseBounds(boundsStr))
        }

        internal fun parseBounds(boundsStr: String): Bounds {
            val boundsArr = boundsStr
                .replace("][", ",")
                .removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .map { it.toInt() }

            return Bounds(
                x = boundsArr[0],
                y = boundsArr[1],
                width = boundsArr[2] - boundsArr[0],
                height = boundsArr[3] - boundsArr[1]
            )
        }
    }
}
