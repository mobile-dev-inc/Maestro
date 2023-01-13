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

    private fun toEvent(press: HIDPress): HIDEvent {
        return hIDEvent {
            this.press = press
        }
    }

}
