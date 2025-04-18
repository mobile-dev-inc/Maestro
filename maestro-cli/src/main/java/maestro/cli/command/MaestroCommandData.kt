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

import com.fasterxml.jackson.annotation.JsonInclude

/** Unified structure for all Maestro commands */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MaestroCommandData(val event: String, val content: CommandContent) {
    companion object {
        fun createTap(x: Int, y: Int): MaestroCommandData {
            return MaestroCommandData(
                    event = "tapOn",
                    content = CommandContent(point = Coordinates(x, y))
            )
        }

        fun createSwipe(
                startX: Int,
                startY: Int,
                endX: Int,
                endY: Int,
                duration: Int = 300
        ): MaestroCommandData {
            return MaestroCommandData(
                    event = "swipe",
                    content =
                            CommandContent(
                                    start = Coordinates(startX, startY),
                                    end = Coordinates(endX, endY),
                                    duration = duration
                            )
            )
        }

        fun createInputText(text: String): MaestroCommandData {
            return MaestroCommandData(event = "inputText", content = CommandContent(text = text))
        }

        fun createEraseText(count: Int): MaestroCommandData {
            return MaestroCommandData(event = "eraseText", content = CommandContent(count = count))
        }

        fun createBack(): MaestroCommandData {
            return MaestroCommandData(event = "back", content = CommandContent())
        }
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class CommandContent(
        val point: Coordinates? = null,
        val start: Coordinates? = null,
        val end: Coordinates? = null,
        val duration: Int? = null,
        val text: String? = null,
        val count: Int? = null
)

data class Coordinates(val x: Int, val y: Int)
