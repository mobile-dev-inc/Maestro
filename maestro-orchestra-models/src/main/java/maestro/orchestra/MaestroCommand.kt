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

data class MaestroCommand(
    val tapOnElement: TapOnElementCommand? = null,
    val tapOnPoint: TapOnPointCommand? = null,
    val scrollCommand: ScrollCommand? = null,
    val swipeCommand: SwipeCommand? = null,
    val backPressCommand: BackPressCommand? = null,
    val assertCommand: AssertCommand? = null,
    val inputTextCommand: InputTextCommand? = null,
    val launchAppCommand: LaunchAppCommand? = null,
    val applyConfigurationCommand: ApplyConfigurationCommand? = null,
    val openLinkCommand: OpenLinkCommand? = null,
) {

    fun description(): String {
        tapOnElement?.let {
            return it.description()
        }

        tapOnPoint?.let {
            return it.description()
        }

        scrollCommand?.let {
            return it.description()
        }

        swipeCommand?.let {
            return it.description()
        }

        backPressCommand?.let {
            return it.description()
        }

        assertCommand?.let {
            return it.description()
        }

        inputTextCommand?.let {
            return it.description()
        }

        launchAppCommand?.let {
            return it.description()
        }

        applyConfigurationCommand?.let {
            return it.description()
        }

        openLinkCommand?.let {
            return it.description()
        }

        return "No op"
    }

}
