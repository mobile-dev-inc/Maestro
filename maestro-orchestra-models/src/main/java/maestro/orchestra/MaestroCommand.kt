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

/**
 * The Mobile.dev platform uses this class in the backend and hence the custom
 * serialization logic. The earlier implementation of this class had a nullable field for
 * each command. Sometime in the future we may move this serialization logic to the backend
 * itself, where it would be more relevant.
 */
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
    val pressKeyCommand: PressKeyCommand? = null,
    val eraseTextCommand: EraseTextCommand? = null,
    val hideKeyboardCommand: HideKeyboardCommand? = null,
) {

    constructor(command: Command) : this(
        tapOnElement = command as? TapOnElementCommand,
        tapOnPoint = command as? TapOnPointCommand,
        scrollCommand = command as? ScrollCommand,
        swipeCommand = command as? SwipeCommand,
        backPressCommand = command as? BackPressCommand,
        assertCommand = command as? AssertCommand,
        inputTextCommand = command as? InputTextCommand,
        launchAppCommand = command as? LaunchAppCommand,
        applyConfigurationCommand = command as? ApplyConfigurationCommand,
        openLinkCommand = command as? OpenLinkCommand,
        pressKeyCommand = command as? PressKeyCommand,
        eraseTextCommand = command as? EraseTextCommand,
        hideKeyboardCommand = command as? HideKeyboardCommand,
    )

    fun asCommand(): Command? = when {
        tapOnElement != null -> tapOnElement
        tapOnPoint != null -> tapOnPoint
        scrollCommand != null -> scrollCommand
        swipeCommand != null -> swipeCommand
        backPressCommand != null -> backPressCommand
        assertCommand != null -> assertCommand
        inputTextCommand != null -> inputTextCommand
        launchAppCommand != null -> launchAppCommand
        applyConfigurationCommand != null -> applyConfigurationCommand
        openLinkCommand != null -> openLinkCommand
        pressKeyCommand != null -> pressKeyCommand
        eraseTextCommand != null -> eraseTextCommand
        hideKeyboardCommand != null -> hideKeyboardCommand
        else -> null
    }

    fun injectEnv(envParameters: Map<String, String>): MaestroCommand {
        return asCommand()
            ?.let { MaestroCommand(it.injectEnv(envParameters)) }
            ?: MaestroCommand()
    }

    fun description(): String {
        return asCommand()?.description() ?: "No op"
    }
}
