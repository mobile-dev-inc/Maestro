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

import kotlin.math.pow
import kotlin.math.sqrt

fun <T : Number> distance(
    fromX: T,
    fromY: T,
    toX: T,
    toY: T
): Float {
    return sqrt(
        (fromX.toDouble() - toX.toDouble()).pow(2.0) + (fromY.toDouble() - toY.toDouble()).pow(2.0)
    ).toFloat()
}

data class Point(
    val x: Int,
    val y: Int
) {

    fun distance(other: Point): Float {
        return distance(x, y, other.x, other.y)
    }
}
