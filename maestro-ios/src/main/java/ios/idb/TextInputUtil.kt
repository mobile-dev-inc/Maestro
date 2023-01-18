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

package ios.idb

import idb.HIDEventKt
import idb.Idb.HIDEvent
import idb.Idb.HIDEvent.HIDPress
import idb.hIDEvent

internal object TextInputUtil {

    /*
     * Implementation is borrowed from Python idb client
     */

    fun textToListOfEvents(text: String): List<HIDEvent> {
        return text.toCharArray()
            .toList()
            .mapNotNull {
                keyMap[it]
            }
            .flatten()
    }

    fun pressWithDuration(
        action: HIDEvent.HIDPressAction,
    ): List<HIDEvent> {
        return listOf(
            toEvent(
                HIDEventKt.hIDPress {
                    this.action = action
                    this.direction = HIDEvent.HIDDirection.DOWN
                }
            ),
            toEvent(
                HIDEventKt.hIDPress {
                    this.action = action
                    this.direction = HIDEvent.HIDDirection.UP
                }
            ),
        )
    }

    fun keyPressToEvents(keycode: Long): List<HIDEvent> {
        return pressWithDuration(
            HIDEventKt.hIDPressAction {
                this.key = HIDEventKt.hIDKey {
                    this.keycode = keycode
                }
            }
        )
    }

    private fun keyDownEvent(keycode: Long): HIDEvent {
        return toEvent(
            HIDEventKt.hIDPress {
                this.action = HIDEventKt.hIDPressAction {
                    this.key = HIDEventKt.hIDKey {
                        this.keycode = keycode
                    }
                }
                this.direction = HIDEvent.HIDDirection.DOWN
            }
        )
    }

    private fun keyUpEvent(keycode: Long): HIDEvent {
        return toEvent(
            HIDEventKt.hIDPress {
                this.action = HIDEventKt.hIDPressAction {
                    this.key = HIDEventKt.hIDKey {
                        this.keycode = keycode
                    }
                }
                this.direction = HIDEvent.HIDDirection.UP
            }
        )
    }

    private fun keyPressShiftedToEvents(keycode: Long): List<HIDEvent> {
        return listOf(
            keyDownEvent(225),
            keyDownEvent(keycode),
            keyUpEvent(keycode),
            keyUpEvent(225),
        )
    }

    private fun toEvent(press: HIDPress): HIDEvent {
        return hIDEvent {
            this.press = press
        }
    }

    private val keyMap = mapOf(
        'a' to keyPressToEvents(4),
        'b' to keyPressToEvents(5),
        'c' to keyPressToEvents(6),
        'd' to keyPressToEvents(7),
        'e' to keyPressToEvents(8),
        'f' to keyPressToEvents(9),
        'g' to keyPressToEvents(10),
        'h' to keyPressToEvents(11),
        'i' to keyPressToEvents(12),
        'j' to keyPressToEvents(13),
        'k' to keyPressToEvents(14),
        'l' to keyPressToEvents(15),
        'm' to keyPressToEvents(16),
        'n' to keyPressToEvents(17),
        'o' to keyPressToEvents(18),
        'p' to keyPressToEvents(19),
        'q' to keyPressToEvents(20),
        'r' to keyPressToEvents(21),
        's' to keyPressToEvents(22),
        't' to keyPressToEvents(23),
        'u' to keyPressToEvents(24),
        'v' to keyPressToEvents(25),
        'w' to keyPressToEvents(26),
        'x' to keyPressToEvents(27),
        'y' to keyPressToEvents(28),
        'z' to keyPressToEvents(29),
        'A' to keyPressShiftedToEvents(4),
        'B' to keyPressShiftedToEvents(5),
        'C' to keyPressShiftedToEvents(6),
        'D' to keyPressShiftedToEvents(7),
        'E' to keyPressShiftedToEvents(8),
        'F' to keyPressShiftedToEvents(9),
        'G' to keyPressShiftedToEvents(10),
        'H' to keyPressShiftedToEvents(11),
        'I' to keyPressShiftedToEvents(12),
        'J' to keyPressShiftedToEvents(13),
        'K' to keyPressShiftedToEvents(14),
        'L' to keyPressShiftedToEvents(15),
        'M' to keyPressShiftedToEvents(16),
        'N' to keyPressShiftedToEvents(17),
        'O' to keyPressShiftedToEvents(18),
        'P' to keyPressShiftedToEvents(19),
        'Q' to keyPressShiftedToEvents(20),
        'R' to keyPressShiftedToEvents(21),
        'S' to keyPressShiftedToEvents(22),
        'T' to keyPressShiftedToEvents(23),
        'U' to keyPressShiftedToEvents(24),
        'V' to keyPressShiftedToEvents(25),
        'W' to keyPressShiftedToEvents(26),
        'X' to keyPressShiftedToEvents(27),
        'Y' to keyPressShiftedToEvents(28),
        'Z' to keyPressShiftedToEvents(29),
        '1' to keyPressToEvents(30),
        '2' to keyPressToEvents(31),
        '3' to keyPressToEvents(32),
        '4' to keyPressToEvents(33),
        '5' to keyPressToEvents(34),
        '6' to keyPressToEvents(35),
        '7' to keyPressToEvents(36),
        '8' to keyPressToEvents(37),
        '9' to keyPressToEvents(38),
        '0' to keyPressToEvents(39),
        '\n' to keyPressToEvents(40),
        ';' to keyPressToEvents(51),
        '=' to keyPressToEvents(46),
        ',' to keyPressToEvents(54),
        '-' to keyPressToEvents(45),
        '.' to keyPressToEvents(55),
        '/' to keyPressToEvents(56),
        '`' to keyPressToEvents(53),
        '[' to keyPressToEvents(47),
        '\\' to keyPressToEvents(49),
        ']' to keyPressToEvents(48),
        '\'' to keyPressToEvents(52),
        ' ' to keyPressToEvents(44),
        '!' to keyPressShiftedToEvents(30),
        '@' to keyPressShiftedToEvents(31),
        '#' to keyPressShiftedToEvents(32),
        '$' to keyPressShiftedToEvents(33),
        '%' to keyPressShiftedToEvents(34),
        '^' to keyPressShiftedToEvents(35),
        '&' to keyPressShiftedToEvents(36),
        '*' to keyPressShiftedToEvents(37),
        '(' to keyPressShiftedToEvents(38),
        ')' to keyPressShiftedToEvents(39),
        '_' to keyPressShiftedToEvents(45),
        '+' to keyPressShiftedToEvents(46),
        '{' to keyPressShiftedToEvents(47),
        '}' to keyPressShiftedToEvents(48),
        ':' to keyPressShiftedToEvents(51),
        '"' to keyPressShiftedToEvents(52),
        '|' to keyPressShiftedToEvents(49),
        '<' to keyPressShiftedToEvents(54),
        '>' to keyPressShiftedToEvents(55),
        '?' to keyPressShiftedToEvents(56),
        '~' to keyPressShiftedToEvents(53),
    )
}
