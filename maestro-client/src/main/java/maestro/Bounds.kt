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

data class Bounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {

    fun center(): Point {
        return Point(
            x = x + width / 2,
            y = y + height / 2
        )
    }

    fun area(): Int {
        return width * height
    }

    fun contains(x: Int, y: Int): Boolean {
        return x in this.x until this.x + width
            && y in this.y until this.y + height
    }

    fun contains(other: Bounds): Boolean {
        return other.x >= x
            && other.y >= y
            && other.x + other.width <= x + width
            && other.y + other.height <= y + height
    }

    fun intersect(other: Bounds): Bounds? {
        val left = maxOf(x, other.x)
        val top = maxOf(y, other.y)
        val right = minOf(x + width, other.x + other.width)
        val bottom = minOf(y + height, other.y + other.height)

        if (right <= left || bottom <= top) {
            return null
        }

        return Bounds(x = left, y = top, width = right - left, height = bottom - top)
    }

    fun toBoundsString(): String {
        return "[$x,$y][${x + width},${y + height}]"
    }

    fun visibleFraction(clip: Bounds?): Double {
        if (width == 0 && height == 0) {
            return 0.0
        }

        if (clip == null) {
            return 0.0
        }

        if (contains(clip)) {
            return 1.0
        }

        return (intersect(clip)?.area() ?: 0).toDouble() / area().toDouble()
    }

}
