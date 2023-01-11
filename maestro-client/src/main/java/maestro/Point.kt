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

abstract class AbstractPoint<T : Number>(
    open val x: T,
    open val y: T
) {

    fun distance(other: AbstractPoint<T>): Float {
        return sqrt(
            (other.x.toDouble() - x.toDouble()).pow(2.0) + (other.y.toDouble() - y.toDouble()).pow(2.0)
        ).toFloat()
    }

}

data class Point(
    override val x: Int,
    override val y: Int
) : AbstractPoint<Int>(x, y) {

    fun normalise(width: Int, height: Int): PointF {
        return PointF(
            x.toFloat() / width,
            y.toFloat() / height
        )
    }

}

data class PointF(
    override val x: Float,
    override val y: Float
) : AbstractPoint<Float>(x, y)

