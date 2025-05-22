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

import maestro.KeyCode
import maestro.Point
import maestro.ScrollDirection
import maestro.SwipeDirection
import maestro.TapRepeat
import maestro.js.JsEngine
import maestro.orchestra.util.Env.evaluateScripts
import maestro.orchestra.util.InputRandomTextHelper
import com.fasterxml.jackson.annotation.JsonIgnore

sealed interface Command {

    @get:JsonIgnore
    val originalDescription: String

    fun description(): String = label ?: originalDescription

    fun evaluateScripts(jsEngine: JsEngine): Command

    fun visible(): Boolean = true

    val label: String?

    val optional: Boolean
}

sealed interface CompositeCommand : Command {

    fun subCommands(): List<MaestroCommand>
    fun config(): MaestroConfig?
}

data class SwipeCommand(
    val direction: SwipeDirection? = null,
    val startPoint: Point? = null,
    val endPoint: Point? = null,
    val elementSelector: ElementSelector? = null,
    val startRelative: String? = null,
    val endRelative: String? = null,
    val duration: Long = DEFAULT_DURATION_IN_MILLIS,
    val waitToSettleTimeoutMs: Int? = null,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = when {
            elementSelector != null && direction != null -> {
                "Swiping in $direction direction on ${elementSelector.description()}"
            }
            direction != null -> {
                "Swiping in $direction direction in $duration ms"
            }
            startPoint != null && endPoint != null -> {
                "Swipe from (${startPoint.x},${startPoint.y}) to (${endPoint.x},${endPoint.y}) in $duration ms"
            }
            startRelative != null && endRelative != null -> {
                "Swipe from ($startRelative) to ($endRelative) in $duration ms"
            }
            else -> "Invalid input to swipe command"
        }

    override fun evaluateScripts(jsEngine: JsEngine): SwipeCommand {
        return copy(
            elementSelector = elementSelector?.evaluateScripts(jsEngine),
            startRelative = startRelative?.evaluateScripts(jsEngine),
            endRelative = endRelative?.evaluateScripts(jsEngine)
        )
    }

    companion object {
        private const val DEFAULT_DURATION_IN_MILLIS = 400L
    }
}

/**
 * @param visibilityPercentage 0-1 Visibility within viewport bounds. 0 not within viewport and 1 fully visible within viewport.
 */
data class ScrollUntilVisibleCommand(
    val selector: ElementSelector,
    val direction: ScrollDirection,
    val scrollDuration: String = DEFAULT_SCROLL_DURATION,
    val visibilityPercentage: Int,
    val timeout: String = DEFAULT_TIMEOUT_IN_MILLIS,
    val waitToSettleTimeoutMs: Int? = null,
    val centerElement: Boolean,
    val originalSpeedValue: String? = scrollDuration,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    val visibilityPercentageNormalized = (visibilityPercentage / 100).toDouble()

    override val originalDescription: String
        get() {
            val baseDescription = "Scrolling $direction until ${selector.description()} is visible"
            val additionalDescription = mutableListOf<String>()
            additionalDescription.add("with speed $originalSpeedValue")
            additionalDescription.add("visibility percentage $visibilityPercentage%")
            additionalDescription.add("timeout $timeout ms")
            waitToSettleTimeoutMs?.let {
                additionalDescription.add("wait to settle $it ms")
            }
            if (centerElement) {
                additionalDescription.add("with centering enabled")
            } else {
                additionalDescription.add("with centering disabled")
            }
            return "$baseDescription ${additionalDescription.joinToString(", ")}"
        }

    private fun String.speedToDuration(): String {
        val duration = ((1000 * (100 - this.toLong()).toDouble() / 100).toLong() + 1)
        return if (duration < 0) {
            DEFAULT_SCROLL_DURATION
        } else duration.toString()
    }

    private fun String.timeoutToMillis(): String {
        return if (this.toLong() < 0) {
            DEFAULT_TIMEOUT_IN_MILLIS
        } else this
    }

    override fun evaluateScripts(jsEngine: JsEngine): ScrollUntilVisibleCommand {
        return copy(
            originalSpeedValue = scrollDuration,
            selector = selector.evaluateScripts(jsEngine),
            scrollDuration = scrollDuration.evaluateScripts(jsEngine).speedToDuration(),
            timeout = timeout.evaluateScripts(jsEngine).timeoutToMillis(),
        )
    }

    companion object {
        const val DEFAULT_TIMEOUT_IN_MILLIS = "20000"
        const val DEFAULT_SCROLL_DURATION = "40"
        const val DEFAULT_ELEMENT_VISIBILITY_PERCENTAGE = 100
        const val DEFAULT_CENTER_ELEMENT = false
    }
}

class ScrollCommand(
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Scroll vertically"

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

    override fun evaluateScripts(jsEngine: JsEngine): ScrollCommand {
        return this
    }
}

class BackPressCommand(
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Press back"

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

    override fun evaluateScripts(jsEngine: JsEngine): BackPressCommand {
        return this
    }
}

class HideKeyboardCommand(
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Hide Keyboard"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return true
    }

    override fun hashCode(): Int {
        return javaClass.hashCode()
    }

    override fun toString(): String {
        return "HideKeyboardCommand()"
    }

    override fun evaluateScripts(jsEngine: JsEngine): HideKeyboardCommand {
        return this
    }
}

data class CopyTextFromCommand(
    val selector: ElementSelector,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Copy text from element with ${selector.description()}"

    override fun evaluateScripts(jsEngine: JsEngine): CopyTextFromCommand {
        return copy(
            selector = selector.evaluateScripts(jsEngine)
        )
    }
}

data class PasteTextCommand(
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Paste text"

    override fun evaluateScripts(jsEngine: JsEngine): PasteTextCommand {
        return this
    }
}

data class TapOnElementCommand(
    val selector: ElementSelector,
    val retryIfNoChange: Boolean? = null,
    val waitUntilVisible: Boolean? = null,
    val longPress: Boolean? = null,
    val repeat: TapRepeat? = null,
    val waitToSettleTimeoutMs: Int? = null,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() {
            val optional = if (optional || selector.optional) "(Optional) " else ""
            return "${tapOnDescription(longPress, repeat)} on $optional${selector.description()}"
        }

    override fun evaluateScripts(jsEngine: JsEngine): TapOnElementCommand {
        return copy(
            selector = selector.evaluateScripts(jsEngine),
        )
    }

    companion object {
        const val DEFAULT_REPEAT_DELAY = 100L
        const val MAX_TIMEOUT_WAIT_TO_SETTLE_MS = 30000
    }
}

@Deprecated("Use TapOnPointV2Command instead")
data class TapOnPointCommand(
    val x: Int,
    val y: Int,
    val retryIfNoChange: Boolean? = null,
    val waitUntilVisible: Boolean? = null,
    val longPress: Boolean? = null,
    val repeat: TapRepeat? = null,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "${tapOnDescription(longPress, repeat)} on point ($x, $y)"

    override fun evaluateScripts(jsEngine: JsEngine): TapOnPointCommand {
        return this
    }
}

data class TapOnPointV2Command(
    val point: String,
    val retryIfNoChange: Boolean? = null,
    val longPress: Boolean? = null,
    val repeat: TapRepeat? = null,
    val waitToSettleTimeoutMs: Int? = null,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "${tapOnDescription(longPress, repeat)} on point ($point)"

    override fun evaluateScripts(jsEngine: JsEngine): TapOnPointV2Command {
        return copy(
            point = point.evaluateScripts(jsEngine),
        )
    }
}

@Deprecated("Use AssertConditionCommand instead")
data class AssertCommand(
    val visible: ElementSelector? = null,
    val notVisible: ElementSelector? = null,
    val timeout: Long? = null,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() {
            val timeoutStr = timeout?.let { " within $timeout ms" } ?: ""
            return when {
                visible != null -> "Assert visible ${visible.description()}" + timeoutStr
                notVisible != null -> "Assert not visible ${notVisible.description()}" + timeoutStr
                else -> "No op"
            }
        }

    override fun evaluateScripts(jsEngine: JsEngine): AssertCommand {
        return copy(
            visible = visible?.evaluateScripts(jsEngine),
            notVisible = notVisible?.evaluateScripts(jsEngine),
        )
    }

    fun toAssertConditionCommand(): AssertConditionCommand {
        return AssertConditionCommand(
            condition = Condition(
                visible = visible,
                notVisible = notVisible,
            ),
            timeout = timeout?.toString(),
        )
    }
}

data class AssertConditionCommand(
    val condition: Condition,
    val timeout: String? = null,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    fun timeoutMs(): Long? {
        return timeout?.replace("_", "")?.toLong()
    }

    override val originalDescription: String
        get() {
            val optional = if (optional || condition.visible?.optional == true || condition.notVisible?.optional == true) "(Optional) " else ""
            return "Assert that $optional${condition.description()}"
        }

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return copy(
            condition = condition.evaluateScripts(jsEngine),
            timeout = timeout?.evaluateScripts(jsEngine)
        )
    }
}

data class AssertNoDefectsWithAICommand(
    override val optional: Boolean = true,
    override val label: String? = null,
) : Command {
    override val originalDescription: String
        get() = "Assert no defects with AI"

    override fun evaluateScripts(jsEngine: JsEngine): Command = this
}

data class AssertWithAICommand(
    val assertion: String,
    override val optional: Boolean = true,
    override val label: String? = null,
) : Command {
    override val originalDescription: String
        get() = "Assert with AI: $assertion"

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return copy(
            assertion = assertion.evaluateScripts(jsEngine),
        )
    }
}

data class ExtractTextWithAICommand(
    val query: String,
    val outputVariable: String,
    override val optional: Boolean = true,
    override val label: String? = null
) : Command {
    override val originalDescription: String
        get() = "Extract text with AI: $query"

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return copy(
            query = query.evaluateScripts(jsEngine),
        )
    }
}

data class InputTextCommand(
    val text: String,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Input text $text"

    override fun evaluateScripts(jsEngine: JsEngine): InputTextCommand {
        return copy(
            text = text.evaluateScripts(jsEngine)
        )
    }
}

data class LaunchAppCommand(
    val appId: String,
    val clearState: Boolean? = null,
    val clearKeychain: Boolean? = null,
    val stopApp: Boolean? = null,
    var permissions: Map<String, String>? = null,
    val launchArguments: Map<String, Any>? = null,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() {
            var result = if (clearState != true) {
                "Launch app \"$appId\""
            } else {
                "Launch app \"$appId\" with clear state"
            }

            if (clearKeychain == true) {
                result += " and clear keychain"
            }

            if (stopApp == false) {
                result += " without stopping app"
            }

            if (launchArguments != null) {
                result += " (launch arguments: ${launchArguments})"
            }

            return result
        }

    override fun evaluateScripts(jsEngine: JsEngine): LaunchAppCommand {
        return copy(
            appId = appId.evaluateScripts(jsEngine),
            launchArguments = launchArguments?.entries?.associate {
                val value = it.value
                it.key.evaluateScripts(jsEngine) to if (value is String) value.evaluateScripts(jsEngine) else it.value
            }
        )
    }
}

data class ApplyConfigurationCommand(
    val config: MaestroConfig,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Apply configuration"

    override fun evaluateScripts(jsEngine: JsEngine): ApplyConfigurationCommand {
        return copy(
            config = config.evaluateScripts(jsEngine),
        )
    }

    override fun visible(): Boolean = false
}

data class OpenLinkCommand(
    val link: String,
    val autoVerify: Boolean? = null,
    val browser: Boolean? = null,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = when {
            browser == true -> if (autoVerify == true) "Open $link with auto verification in browser" else "Open $link in browser"
            else -> if (autoVerify == true) "Open $link with auto verification" else "Open $link"
        }

    override fun evaluateScripts(jsEngine: JsEngine): OpenLinkCommand {
        return copy(
            link = link.evaluateScripts(jsEngine),
        )
    }
}

data class PressKeyCommand(
    val code: KeyCode,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Press ${code.description} key"

    override fun evaluateScripts(jsEngine: JsEngine): PressKeyCommand {
        return this
    }
}

data class EraseTextCommand(
    val charactersToErase: Int?,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = when (charactersToErase) {
            null -> "Erase text"
            else -> "Erase $charactersToErase characters"
        }

    override fun evaluateScripts(jsEngine: JsEngine): EraseTextCommand {
        return this
    }

}

data class TakeScreenshotCommand(
    val path: String,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Take screenshot $path"

    override fun evaluateScripts(jsEngine: JsEngine): TakeScreenshotCommand {
        return copy(
            path = path.evaluateScripts(jsEngine),
        )
    }
}

data class StopAppCommand(
    val appId: String,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Stop $appId"

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return copy(
            appId = appId.evaluateScripts(jsEngine),
        )
    }
}

data class KillAppCommand(
    val appId: String,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Kill $appId"

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return copy(
            appId = appId.evaluateScripts(jsEngine),
        )
    }
}

data class ClearStateCommand(
    val appId: String,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Clear state of $appId"

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return copy(
            appId = appId.evaluateScripts(jsEngine),
        )
    }
}

class ClearKeychainCommand(
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Clear keychain"

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return this
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return true
    }

    override fun hashCode(): Int {
        return javaClass.hashCode()
    }
}

enum class InputRandomType {
    NUMBER, TEXT, TEXT_EMAIL_ADDRESS, TEXT_PERSON_NAME,
}

data class InputRandomCommand(
    val inputType: InputRandomType? = InputRandomType.TEXT,
    val length: Int? = 8,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    fun genRandomString(): String {
        val lengthNonNull = length ?: 8
        val finalLength = if (lengthNonNull <= 0) 8 else lengthNonNull

        return when (inputType) {
            InputRandomType.NUMBER -> InputRandomTextHelper.getRandomNumber(finalLength)
            InputRandomType.TEXT -> InputRandomTextHelper.getRandomText(finalLength)
            InputRandomType.TEXT_EMAIL_ADDRESS -> InputRandomTextHelper.randomEmail()
            InputRandomType.TEXT_PERSON_NAME -> InputRandomTextHelper.randomPersonName()
            else -> InputRandomTextHelper.getRandomText(finalLength)
        }
    }

    override val originalDescription: String
        get() = "Input text random $inputType"

    override fun evaluateScripts(jsEngine: JsEngine): InputRandomCommand {
        return this
    }
}

data class RunFlowCommand(
    val commands: List<MaestroCommand>,
    val condition: Condition? = null,
    val sourceDescription: String? = null,
    val config: MaestroConfig?,
    override val label: String? = null,
    override val optional: Boolean = false,
) : CompositeCommand {

    override fun subCommands(): List<MaestroCommand> {
        return commands
    }

    override fun config(): MaestroConfig? {
        return config
    }

    override val originalDescription: String
        get() {
            val runDescription = if (sourceDescription != null) {
                "Run $sourceDescription"
            } else {
                "Run flow"
            }

            return if (condition == null) {
                runDescription
            } else {
                "$runDescription when ${condition.description()}"
            }
        }

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return copy(
            condition = condition?.evaluateScripts(jsEngine),
            config = config?.evaluateScripts(jsEngine),
        )
    }
}

data class SetLocationCommand(
    val latitude: String,
    val longitude: String,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Set location (${latitude}, ${longitude})"

    override fun evaluateScripts(jsEngine: JsEngine): SetLocationCommand {
        return copy(
            latitude = latitude.evaluateScripts(jsEngine),
            longitude = longitude.evaluateScripts(jsEngine),
        )
    }
}

data class RepeatCommand(
    val times: String? = null,
    val condition: Condition? = null,
    val commands: List<MaestroCommand>,
    override val label: String? = null,
    override val optional: Boolean = false,
) : CompositeCommand {

    override fun subCommands(): List<MaestroCommand> {
        return commands
    }

    override fun config(): MaestroConfig? {
        return null
    }

    override val originalDescription: String
        get() {
            val timesInt = times?.toIntOrNull() ?: 1

            return when {
                condition != null && timesInt > 1 -> {
                    "Repeat while ${condition.description()} (up to $timesInt times)"
                }
                condition != null -> {
                    "Repeat while ${condition.description()}"
                }
                timesInt > 1 -> "Repeat $timesInt times"
                else -> "Repeat indefinitely"
            }
        }

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return copy(
            times = times?.evaluateScripts(jsEngine),
        )
    }

}

data class RetryCommand(
    val maxRetries: String? = null,
    val commands: List<MaestroCommand>,
    val config: MaestroConfig?,
    override val label: String? = null,
    override val optional: Boolean = false,
) : CompositeCommand {

    override fun subCommands(): List<MaestroCommand> {
        return commands
    }

    override fun config(): MaestroConfig? {
        return null
    }

    override val originalDescription: String
        get() {
            val maxAttempts = maxRetries?.toIntOrNull() ?: 1
            return "Retry $maxAttempts times"
        }

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return copy(
            maxRetries = maxRetries?.evaluateScripts(jsEngine),
        )
    }

}

data class DefineVariablesCommand(
    val env: Map<String, String>,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Define variables"

    override fun evaluateScripts(jsEngine: JsEngine): DefineVariablesCommand {
        return copy(
            env = env.mapValues { (_, value) ->
                value.evaluateScripts(jsEngine)
            }
        )
    }

    override fun visible(): Boolean = false
}

data class RunScriptCommand(
    val script: String,
    val env: Map<String, String> = emptyMap(),
    val sourceDescription: String,
    val condition: Condition?,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = if (condition == null) {
            "Run $sourceDescription"
        } else {
            "Run $sourceDescription when ${condition.description()}"
        }

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return copy(
            env = env.mapValues { (_, value) ->
                value.evaluateScripts(jsEngine)
            },
            condition = condition?.evaluateScripts(jsEngine),
        )
    }
}

data class WaitForAnimationToEndCommand(
    val timeout: Long?,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Wait for animation to end"

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return this
    }
}

data class EvalScriptCommand(
    val scriptString: String,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Run $scriptString"

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return this
    }

}

data class TravelCommand(
    val points: List<GeoPoint>,
    val speedMPS: Double? = null,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    data class GeoPoint(
        val latitude: String,
        val longitude: String,
    ) {

        fun getDistanceInMeters(another: GeoPoint): Double {
            val earthRadius = 6371 // in kilometers
            val oLat = Math.toRadians(latitude.toDouble())
            val oLon = Math.toRadians(longitude.toDouble())

            val aLat = Math.toRadians(another.latitude.toDouble())
            val aLon = Math.toRadians(another.longitude.toDouble())

            val dLat = Math.toRadians(aLat - oLat)
            val dLon = Math.toRadians(aLon - oLon)

            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(oLat)) * Math.cos(Math.toRadians(aLat)) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)

            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            val distance = earthRadius * c * 1000 // convert to meters

            return distance
        }

    }

    override val originalDescription: String
        get() = "Travel path ${points.joinToString { "(${it.latitude}, ${it.longitude})" }}"

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return copy(
            points = points.map {
                it.copy(
                    latitude = it.latitude.evaluateScripts(jsEngine),
                    longitude = it.longitude.evaluateScripts(jsEngine)
                )
            }
        )
    }

}

data class StartRecordingCommand(
    val path: String,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Start recording $path"

    override fun evaluateScripts(jsEngine: JsEngine): StartRecordingCommand {
        return copy(
            path = path.evaluateScripts(jsEngine),
        )
    }
}

data class AddMediaCommand(
    val mediaPaths: List<String>,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Adding media files(${mediaPaths.size}) to the device"

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return copy(
            mediaPaths = mediaPaths.map { it.evaluateScripts(jsEngine) }
        )
    }
}


data class StopRecordingCommand(
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {

    override val originalDescription: String
        get() = "Stop recording"

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return this
    }
}

enum class AirplaneValue {
    Enable,
    Disable,
}

data class SetAirplaneModeCommand(
    val value: AirplaneValue,
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {
    override val originalDescription: String
        get() = when (value) {
            AirplaneValue.Enable -> "Enable airplane mode"
            AirplaneValue.Disable -> "Disable airplane mode"
        }

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return this
    }
}

data class ToggleAirplaneModeCommand(
    override val label: String? = null,
    override val optional: Boolean = false,
) : Command {
    override val originalDescription: String
        get() = "Toggle airplane mode"

    override fun evaluateScripts(jsEngine: JsEngine): Command {
        return this
    }
}

internal fun tapOnDescription(isLongPress: Boolean?, repeat: TapRepeat?): String {
    return if (isLongPress == true) "Long press"
    else if (repeat != null) {
        when (repeat.repeat) {
            1 -> "Tap"
            2 -> "Double tap"
            else -> "Tap x${repeat.repeat}"
        }
    } else "Tap"
}
