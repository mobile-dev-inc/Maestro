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

package maestro.orchestra

import maestro.Point

interface Command {

    fun description(): String

}

data class SwipeCommand(
    val startPoint: Point,
    val endPoint: Point,
) : Command {

    override fun description(): String {
        return "Swipe from (${startPoint.x},${startPoint.y}) to (${endPoint.x},${endPoint.y})"
    }

}

class ScrollCommand : Command {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return true
    }

    override fun hashCode(): Int {
        return javaClass.hashCode()
    }

    override fun toString(): String {
        return "ScrollCommand()"
    }

    override fun description(): String {
        return "Scroll vertically"
    }

}

class BackPressCommand : Command {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return true
    }

    override fun hashCode(): Int {
        return javaClass.hashCode()
    }

    override fun toString(): String {
        return "BackPressCommand()"
    }

    override fun description(): String {
        return "Press back"
    }

}

data class TapOnElementCommand(
    val selector: ElementSelector,
    val retryIfNoChange: Boolean? = null,
    val waitUntilVisible: Boolean? = null,
) : Command {

    override fun description(): String {
        return "Tap on ${selector.description()}"
    }

}

data class TapOnPointCommand(
    val x: Int,
    val y: Int,
    val retryIfNoChange: Boolean? = null,
    val waitUntilVisible: Boolean? = null,
) : Command {

    override fun description(): String {
        return "Tap on point ($x, $y)"
    }
}

data class AssertCommand(
    val visible: ElementSelector? = null,
) : Command {

    override fun description(): String {
        if (visible != null) {
            return "Assert visible ${visible.description()}"
        }

        return "No op"
    }

}

data class InputTextCommand(
    val text: String
) : Command {

    override fun description(): String {
        return "Input text $text"
    }

}

data class LaunchAppCommand(
    val appId: String,
    val clearState: Boolean? = null,
) : Command {

    override fun description(): String {
        return if (clearState != true) {
            "Launch app \"$appId\""
        } else {
            "Launch app \"$appId\" with clear state"
        }
    }

}

data class ApplyConfigurationCommand(
    val config: MaestroConfig,
) : Command {

    override fun description(): String {
        return "Apply configuration"
    }
}

data class OpenLinkCommand(
    val link: String
) : Command {

    override fun description(): String {
        return "Open $link"
    }
}
