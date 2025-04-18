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
 */

package maestro.cli.command

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Timer
import java.util.TimerTask
import kotlin.math.pow
import kotlin.math.sqrt
import maestro.TreeNode

data class EventData(
        val timestamp: String,
        val device: String,
        val eventType: String,
        val eventCode: String,
        val eventValue: String,
        val raw: String
)

sealed class MaestroCommand {
    data class TapCommand(val x: Int, val y: Int) : MaestroCommand() {
        override fun toString(): String = "tapOn: point: { x: $x, y: $y }"
    }

    data class SwipeCommand(
            val startX: Int,
            val startY: Int,
            val endX: Int,
            val endY: Int,
    ) : MaestroCommand() {
        override fun toString(): String =
                "swipe: { start: { x: $startX, y: $startY }, end: { x: $endX, y: $endY } }"
    }

    data class InputTextCommand(val text: String) : MaestroCommand() {
        override fun toString(): String = "inputText: \"$text\""
    }

    data class EraseTextCommand(val count: Int) : MaestroCommand() {
        override fun toString(): String = "eraseText: $count"
    }

    data class BackCommand(val value: Boolean = true) : MaestroCommand() {
        override fun toString(): String = "back: true"
    }
}

class DeviceDetectionException(message: String) : Exception(message)

class MaestroEventParser {
    private var currentTrackingId: String? = null
    private var trackingStartPosition: Pair<Int?, Int?> = Pair(null, null)
    private var lastPosition: Pair<Int?, Int?> = Pair(null, null)
    private var positionCount = 0

    private var pendingText = StringBuilder()
    private var backspaceCount = 0
    private var isShiftPressed = false
    private var lastEventType: String? = null
    private var lastKeyTime = 0L

    private var textInputTimer: Timer? = null
    private var backspaceTimer: Timer? = null

    private val TEXT_INPUT_TIMEOUT = 800L
    private val BACKSPACE_TIMEOUT = 800L

    private var touchXMin = 0
    private var touchXMax = 0
    private var touchYMin = 0
    private var touchYMax = 0
    private var screenWidth = 0
    private var screenHeight = 0

    private val ABS_MT_POSITION_X = "0035"
    private val ABS_MT_POSITION_Y = "0036"

    private var commandCallback: ((MaestroCommandData) -> Unit)? = null
    private var preEventHierarchy: TreeNode? = null

    init {
        // Initialize device info during startup
        detectScreenSize()
        detectTouchRanges()

        if (screenWidth <= 0 || screenHeight <= 0) {
            throw DeviceDetectionException("Failed to detect screen dimensions")
        }

        if (touchXMax <= touchXMin || touchYMax <= touchYMin) {
            throw DeviceDetectionException("Failed to detect valid touch coordinate ranges")
        }
    }

    fun setCommandCallback(callback: (MaestroCommandData) -> Unit) {
        this.commandCallback = callback
    }

    fun setPreEventHierarchy(hierarchy: TreeNode?) {
        this.preEventHierarchy = hierarchy
    }

    private fun detectScreenSize() {
        try {
            val process = Runtime.getRuntime().exec("adb shell wm size")
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output =
                    reader.readLine()
                            ?: throw DeviceDetectionException("No output from 'adb shell wm size'")

            val regex = Regex("Physical size: (\\d+)x(\\d+)")
            val matchResult =
                    regex.find(output)
                            ?: throw DeviceDetectionException(
                                    "Could not parse screen dimensions from: $output"
                            )

            screenWidth = matchResult.groupValues[1].toInt()
            screenHeight = matchResult.groupValues[2].toInt()

            if (screenWidth <= 0 || screenHeight <= 0) {
                throw DeviceDetectionException(
                        "Invalid screen dimensions detected: ${screenWidth}x${screenHeight}"
                )
            }

            process.waitFor()
        } catch (e: Exception) {
            if (e is DeviceDetectionException) throw e
            throw DeviceDetectionException("Error detecting screen size: ${e.message}")
        }
    }

    private fun detectTouchRanges() {
        var foundXRange = false
        var foundYRange = false

        try {
            val process = Runtime.getRuntime().exec("adb shell getevent -p")
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            val xRangePattern =
                    Regex("$ABS_MT_POSITION_X\\s+:\\s+value.*min\\s+(\\d+),\\s+max\\s+(\\d+)")
            val yRangePattern =
                    Regex("$ABS_MT_POSITION_Y\\s+:\\s+value.*min\\s+(\\d+),\\s+max\\s+(\\d+)")

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val xMatch = xRangePattern.find(line ?: "")
                if (xMatch != null) {
                    touchXMin = xMatch.groupValues[1].toInt()
                    touchXMax = xMatch.groupValues[2].toInt()
                    foundXRange = true
                }

                val yMatch = yRangePattern.find(line ?: "")
                if (yMatch != null) {
                    touchYMin = yMatch.groupValues[1].toInt()
                    touchYMax = yMatch.groupValues[2].toInt()
                    foundYRange = true
                }

                if (foundXRange && foundYRange) {
                    break
                }
            }

            if (!foundXRange) {
                throw DeviceDetectionException(
                        "Could not find X coordinate range (ABS_MT_POSITION_X)"
                )
            }

            if (!foundYRange) {
                throw DeviceDetectionException(
                        "Could not find Y coordinate range (ABS_MT_POSITION_Y)"
                )
            }

            if (touchXMax <= touchXMin) {
                throw DeviceDetectionException("Invalid X coordinate range: $touchXMin-$touchXMax")
            }

            if (touchYMax <= touchYMin) {
                throw DeviceDetectionException("Invalid Y coordinate range: $touchYMin-$touchYMax")
            }

            process.waitFor()
        } catch (e: Exception) {
            if (e is DeviceDetectionException) throw e
            throw DeviceDetectionException("Error detecting touch ranges: ${e.message}")
        }
    }

    private fun normalizedToPixelX(normalizedValue: Int): Int {
        val percent = (normalizedValue - touchXMin).toFloat() / (touchXMax - touchXMin)
        return (percent * screenWidth).toInt()
    }

    private fun normalizedToPixelY(normalizedValue: Int): Int {
        val percent = (normalizedValue - touchYMin).toFloat() / (touchYMax - touchYMin)
        return (percent * screenHeight).toInt()
    }

    /** Parses event data from getevent output */
    fun parseEventData(eventString: String): EventData? {
        try {
            val parts = eventString.split(" ").filter { it.isNotEmpty() }
            if (parts.size < 5) return null

            val timestamp = parts[0].removeSurrounding("[", "]")
            val device = parts[1]
            val eventType = parts[2]
            val eventCode = parts[3]
            val eventValue = parts[4]

            return EventData(
                    timestamp = timestamp,
                    device = device,
                    eventType = eventType,
                    eventCode = eventCode,
                    eventValue = eventValue,
                    raw = eventString
            )
        } catch (e: Exception) {
            println("Failed to parse event data: ${e.message}, event: $eventString")
            return null
        }
    }

    fun handleEvent(event: EventData, verbose: Boolean = false) {
        val isKeyDown = event.raw.contains("KEY_") && event.raw.contains("DOWN")
        val isKeyUp = event.raw.contains("KEY_") && event.raw.contains("UP")
        val isKeyEvent = isKeyDown || isKeyUp

        if (lastEventType == "key" && event.raw.contains("ABS_MT") && !isKeyEvent) {
            commitPendingText(verbose)
            commitBackspaces(verbose)
        }

        if (event.raw.contains("ABS_MT")) {
            lastEventType = "touch"
            handleTouchEvent(event, verbose)
        } else if (isKeyEvent) {
            lastEventType = "key"
            handleKeyEvent(event, verbose)
        }
    }

    fun handleTouchEvent(event: EventData, verbose: Boolean = false) {
        try {
            val trackingStartMatch = Regex("ABS_MT_TRACKING_ID\\s+([0-9a-fA-F]+)").find(event.raw)
            if (trackingStartMatch != null && !event.raw.contains("ffffffff")) {
                currentTrackingId = trackingStartMatch.groupValues[1]
                trackingStartPosition = Pair(null, null)
                lastPosition = Pair(null, null)
                positionCount = 0
                if (verbose) {
                    println("Touch sequence started with tracking ID: $currentTrackingId")
                }
            }

            if (currentTrackingId != null) {
                val xMatch = Regex("ABS_MT_POSITION_X\\s+([0-9a-fA-F]+)").find(event.raw)
                if (xMatch != null) {
                    val rawX = Integer.parseInt(xMatch.groupValues[1], 16)
                    val pixelX = normalizedToPixelX(rawX)

                    if (trackingStartPosition.first == null) {
                        trackingStartPosition = Pair(pixelX, trackingStartPosition.second)
                    }
                    lastPosition = Pair(pixelX, lastPosition.second)
                    positionCount++

                    if (verbose) {
                        println(
                                "X coordinate: raw=$rawX, pixel=$pixelX (range: $touchXMin-$touchXMax → 0-$screenWidth)"
                        )
                    }
                }

                val yMatch = Regex("ABS_MT_POSITION_Y\\s+([0-9a-fA-F]+)").find(event.raw)
                if (yMatch != null) {
                    val rawY = Integer.parseInt(yMatch.groupValues[1], 16)
                    val pixelY = normalizedToPixelY(rawY)

                    if (trackingStartPosition.second == null) {
                        trackingStartPosition = Pair(trackingStartPosition.first, pixelY)
                    }
                    lastPosition = Pair(lastPosition.first, pixelY)
                    positionCount++

                    if (verbose) {
                        println(
                                "Y coordinate: raw=$rawY, pixel=$pixelY (range: $touchYMin-$touchYMax → 0-$screenHeight)"
                        )
                    }
                }

                if (event.raw.contains("ABS_MT_TRACKING_ID") && event.raw.contains("ffffffff")) {
                    finalizeGesture(verbose)
                    currentTrackingId = null
                }
            }
        } catch (e: Exception) {
            println("Error processing touch event: ${e.message}")
        }
    }

    private fun handleKeyEvent(event: EventData, verbose: Boolean = false) {
        val now = System.currentTimeMillis()
        if (verbose) {
            println("Processing key event: ${event.raw}, timeGap: ${now - lastKeyTime}ms")
        }
        lastKeyTime = now

        if (!event.raw.contains("DOWN")) {
            return
        }

        if (event.raw.contains("KEY_LEFTSHIFT") || event.raw.contains("KEY_RIGHTSHIFT")) {
            isShiftPressed = event.raw.contains("DOWN")
            if (verbose) {
                println("Shift key ${if (isShiftPressed) "pressed" else "released"}")
            }
            return
        }

        val keyMatch = Regex("KEY_([A-Z0-9]+)\\s+DOWN").find(event.raw)
        if (keyMatch != null) {
            val key = keyMatch.groupValues[1]
            if (verbose) {
                println("Key pressed: $key, shift: $isShiftPressed")
            }

            when (key) {
                "BACKSPACE" -> {
                    backspaceCount++
                    if (verbose) {
                        println("Backspace pressed, count: $backspaceCount")
                    }

                    if (pendingText.isNotEmpty()) {
                        commitPendingText(verbose)
                    }

                    backspaceTimer?.cancel()

                    backspaceTimer = Timer()
                    backspaceTimer?.schedule(
                            object : TimerTask() {
                                override fun run() {
                                    commitBackspaces(verbose)
                                }
                            },
                            BACKSPACE_TIMEOUT
                    )
                }
                "ENTER" -> {
                    commitBackspaces(verbose)

                    pendingText.append('\n')
                    commitPendingText(verbose)
                }
                "BACK" -> {
                    commitPendingText(verbose)
                    commitBackspaces(verbose)

                    val backCommand =
                            MaestroCommandData.createBack(preEventHierarchy = preEventHierarchy)
                    commandCallback?.invoke(backCommand)
                    if (verbose) {
                        println("Detected Maestro command: $backCommand")
                    }
                }
                else -> {
                    commitBackspaces(verbose)

                    val char = keyToChar(key, isShiftPressed)
                    if (char != null) {
                        pendingText.append(char)
                        if (verbose) {
                            println("Added char: \"$char\", text is now: \"$pendingText\"")
                        }
                    }

                    textInputTimer?.cancel()

                    textInputTimer = Timer()
                    textInputTimer?.schedule(
                            object : TimerTask() {
                                override fun run() {
                                    commitPendingText(verbose)
                                }
                            },
                            TEXT_INPUT_TIMEOUT
                    )
                }
            }
        }
    }

    private fun finalizeGesture(verbose: Boolean = false) {
        val startX = trackingStartPosition.first
        val startY = trackingStartPosition.second
        val endX = lastPosition.first
        val endY = lastPosition.second

        if (startX == null || startY == null || endX == null || endY == null) {
            if (verbose) {
                println("Touch sequence ended but positions are incomplete")
            }
            return
        }

        val distance = sqrt((endX - startX).toDouble().pow(2) + (endY - startY).toDouble().pow(2))

        if (verbose) {
            println("Touch sequence ended. Distance: $distance, Position count: $positionCount")
            println("Start position (pixels): ($startX, $startY)")
            println("End position (pixels): ($endX, $endY)")
        }

        commitPendingText(verbose)
        commitBackspaces(verbose)

        // If significant distance and multiple position updates, it's a swipe
        if (distance > 50 && positionCount >= 4) {
            val swipeCommand =
                    MaestroCommandData.createSwipe(
                            startX = startX,
                            startY = startY,
                            endX = endX,
                            endY = endY,
                            preEventHierarchy = preEventHierarchy
                    )

            commandCallback?.invoke(swipeCommand)
            if (verbose) {
                println("Detected Maestro command: $swipeCommand")
            }
        } else {
            val tapCommand =
                    MaestroCommandData.createTap(
                            x = startX,
                            y = startY,
                            preEventHierarchy = preEventHierarchy
                    )

            commandCallback?.invoke(tapCommand)
            if (verbose) {
                println("Detected Maestro command: $tapCommand")
            }
        }
    }

    private fun commitPendingText(verbose: Boolean = false) {
        if (pendingText.isNotEmpty()) {
            val inputCommand =
                    MaestroCommandData.createInputText(
                            pendingText.toString(),
                            preEventHierarchy = preEventHierarchy
                    )

            commandCallback?.invoke(inputCommand)
            if (verbose) {
                println("Committed text input: \"${pendingText}\"")
            }
            pendingText.clear()
        }

        textInputTimer?.cancel()
        textInputTimer = null
    }

    private fun commitBackspaces(verbose: Boolean = false) {
        if (backspaceCount > 0) {
            val eraseCommand =
                    MaestroCommandData.createEraseText(
                            backspaceCount,
                            preEventHierarchy = preEventHierarchy
                    )

            commandCallback?.invoke(eraseCommand)
            if (verbose) {
                println("Committed eraseText: $backspaceCount characters")
            }
            backspaceCount = 0
        }

        backspaceTimer?.cancel()
        backspaceTimer = null
    }

    private fun keyToChar(key: String, isShiftPressed: Boolean): Char? {
        if (key.length == 1 && key[0] in 'A'..'Z') {
            return if (isShiftPressed) key[0] else key[0].lowercaseChar()
        }

        if (key.length == 1 && key[0] in '0'..'9') {
            if (!isShiftPressed) {
                return key[0]
            }
            return when (key) {
                "1" -> '!'
                "2" -> '@'
                "3" -> '#'
                "4" -> '$'
                "5" -> '%'
                "6" -> '^'
                "7" -> '&'
                "8" -> '*'
                "9" -> '('
                "0" -> ')'
                else -> key[0]
            }
        }

        // Handle special keys
        return when (key) {
            "SPACE" -> ' '
            "TAB" -> '\t'
            "DOT" -> if (isShiftPressed) '>' else '.'
            "COMMA" -> if (isShiftPressed) '<' else ','
            "SEMICOLON" -> if (isShiftPressed) ':' else ';'
            "APOSTROPHE" -> if (isShiftPressed) '"' else '\''
            "SLASH" -> if (isShiftPressed) '?' else '/'
            "BACKSLASH" -> if (isShiftPressed) '|' else '\\'
            "MINUS" -> if (isShiftPressed) '_' else '-'
            "EQUALS" -> if (isShiftPressed) '+' else '='
            "LEFTBRACE" -> if (isShiftPressed) '{' else '['
            "RIGHTBRACE" -> if (isShiftPressed) '}' else ']'
            "GRAVE" -> if (isShiftPressed) '~' else '`'
            else -> null
        }
    }
}
