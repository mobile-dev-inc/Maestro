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

import maestro.js.JsEngine

/**
 * The Mobile.dev platform uses this class in the backend and hence the custom
 * serialization logic. The earlier implementation of this class had a nullable field for
 * each command. Sometime in the future we may move this serialization logic to the backend
 * itself, where it would be more relevant.
 */
data class MaestroCommand(
    val tapOnElement: TapOnElementCommand? = null,
    @Deprecated("Use tapOnPointV2Command") val tapOnPoint: TapOnPointCommand? = null,
    val tapOnPointV2Command: TapOnPointV2Command? = null,
    val scrollCommand: ScrollCommand? = null,
    val swipeCommand: SwipeCommand? = null,
    val backPressCommand: BackPressCommand? = null,
    @Deprecated("Use assertConditionCommand") val assertCommand: AssertCommand? = null,
    val assertConditionCommand: AssertConditionCommand? = null,
    val assertVisualCommand: AssertVisualCommand? = null,
    val assertScreenshotCommand: AssertScreenshotCommand? = null,
    val assertNoDefectsWithAICommand: AssertNoDefectsWithAICommand? = null,
    val assertWithAICommand: AssertWithAICommand? = null,
    val extractTextWithAICommand: ExtractTextWithAICommand? = null,
    val extractPointWithAICommand: ExtractPointWithAICommand? = null,
    val extractComponentWithAICommand: ExtractComponentWithAICommand? = null,
    val inputTextCommand: InputTextCommand? = null,
    val inputRandomTextCommand: InputRandomCommand? = null,
    val launchAppCommand: LaunchAppCommand? = null,
    val setPermissionsCommand: SetPermissionsCommand? = null,
    val applyConfigurationCommand: ApplyConfigurationCommand? = null,
    val openLinkCommand: OpenLinkCommand? = null,
    val pressKeyCommand: PressKeyCommand? = null,
    val eraseTextCommand: EraseTextCommand? = null,
    val hideKeyboardCommand: HideKeyboardCommand? = null,
    val takeScreenshotCommand: TakeScreenshotCommand? = null,
    val stopAppCommand: StopAppCommand? = null,
    val killAppCommand: KillAppCommand? = null,
    val clearStateCommand: ClearStateCommand? = null,
    val clearKeychainCommand: ClearKeychainCommand? = null,
    val runFlowCommand: RunFlowCommand? = null,
    val setLocationCommand: SetLocationCommand? = null,
    var setOrientationCommand: SetOrientationCommand? = null,
    val repeatCommand: RepeatCommand? = null,
    val copyTextCommand: CopyTextFromCommand? = null,
    val setClipboardCommand: SetClipboardCommand? = null,
    val pasteTextCommand: PasteTextCommand? = null,
    val defineVariablesCommand: DefineVariablesCommand? = null,
    val runScriptCommand: RunScriptCommand? = null,
    val waitForAnimationToEndCommand: WaitForAnimationToEndCommand? = null,
    val evalScriptCommand: EvalScriptCommand? = null,
    val scrollUntilVisible: ScrollUntilVisibleCommand? = null,
    val travelCommand: TravelCommand? = null,
    val startRecordingCommand: StartRecordingCommand? = null,
    val stopRecordingCommand: StopRecordingCommand? = null,
    val addMediaCommand: AddMediaCommand? = null,
    val setAirplaneModeCommand: SetAirplaneModeCommand? = null,
    val toggleAirplaneModeCommand: ToggleAirplaneModeCommand? = null,
    val retryCommand: RetryCommand? = null,
) {

    constructor(command: Command) : this(
        tapOnElement = command as? TapOnElementCommand,
        tapOnPoint = command as? TapOnPointCommand,
        tapOnPointV2Command = command as? TapOnPointV2Command,
        scrollCommand = command as? ScrollCommand,
        swipeCommand = command as? SwipeCommand,
        backPressCommand = command as? BackPressCommand,
        assertCommand = command as? AssertCommand,
        assertConditionCommand = command as? AssertConditionCommand,
        assertNoDefectsWithAICommand = command as? AssertNoDefectsWithAICommand,
        assertWithAICommand = command as? AssertWithAICommand,
        extractTextWithAICommand = command as? ExtractTextWithAICommand,
        extractPointWithAICommand = command as? ExtractPointWithAICommand,
        extractComponentWithAICommand = command as? ExtractComponentWithAICommand,
        inputTextCommand = command as? InputTextCommand,
        inputRandomTextCommand = command as? InputRandomCommand,
        assertVisualCommand = command as? AssertVisualCommand,
        assertScreenshotCommand = command as? AssertScreenshotCommand,
        launchAppCommand = command as? LaunchAppCommand,
        setPermissionsCommand = command as? SetPermissionsCommand,
        applyConfigurationCommand = command as? ApplyConfigurationCommand,
        openLinkCommand = command as? OpenLinkCommand,
        pressKeyCommand = command as? PressKeyCommand,
        eraseTextCommand = command as? EraseTextCommand,
        hideKeyboardCommand = command as? HideKeyboardCommand,
        takeScreenshotCommand = command as? TakeScreenshotCommand,
        stopAppCommand = command as? StopAppCommand,
        killAppCommand = command as? KillAppCommand,
        clearStateCommand = command as? ClearStateCommand,
        clearKeychainCommand = command as? ClearKeychainCommand,
        runFlowCommand = command as? RunFlowCommand,
        setLocationCommand = command as? SetLocationCommand,
        setOrientationCommand = command as? SetOrientationCommand,
        repeatCommand = command as? RepeatCommand,
        copyTextCommand = command as? CopyTextFromCommand,
        setClipboardCommand = command as? SetClipboardCommand,
        pasteTextCommand = command as? PasteTextCommand,
        defineVariablesCommand = command as? DefineVariablesCommand,
        runScriptCommand = command as? RunScriptCommand,
        waitForAnimationToEndCommand = command as? WaitForAnimationToEndCommand,
        evalScriptCommand = command as? EvalScriptCommand,
        scrollUntilVisible = command as? ScrollUntilVisibleCommand,
        travelCommand = command as? TravelCommand,
        startRecordingCommand = command as? StartRecordingCommand,
        stopRecordingCommand = command as? StopRecordingCommand,
        addMediaCommand = command as? AddMediaCommand,
        setAirplaneModeCommand = command as? SetAirplaneModeCommand,
        toggleAirplaneModeCommand = command as? ToggleAirplaneModeCommand,
        retryCommand = command as? RetryCommand
    )

    fun asCommand(): Command? = when {
        tapOnElement != null -> tapOnElement
        tapOnPoint != null -> tapOnPoint
        tapOnPointV2Command != null -> tapOnPointV2Command
        scrollCommand != null -> scrollCommand
        swipeCommand != null -> swipeCommand
        backPressCommand != null -> backPressCommand
        assertCommand != null -> assertCommand
        assertConditionCommand != null -> assertConditionCommand
        assertNoDefectsWithAICommand != null -> assertNoDefectsWithAICommand
        assertWithAICommand != null -> assertWithAICommand
        extractTextWithAICommand != null -> extractTextWithAICommand
        extractPointWithAICommand != null -> extractPointWithAICommand
        extractComponentWithAICommand != null -> extractComponentWithAICommand
        inputTextCommand != null -> inputTextCommand
        inputRandomTextCommand != null -> inputRandomTextCommand
        launchAppCommand != null -> launchAppCommand
        setPermissionsCommand != null -> setPermissionsCommand
        applyConfigurationCommand != null -> applyConfigurationCommand
        openLinkCommand != null -> openLinkCommand
        pressKeyCommand != null -> pressKeyCommand
        assertVisualCommand != null -> assertVisualCommand
        eraseTextCommand != null -> eraseTextCommand
        hideKeyboardCommand != null -> hideKeyboardCommand
        takeScreenshotCommand != null -> takeScreenshotCommand
        stopAppCommand != null -> stopAppCommand
        killAppCommand != null -> killAppCommand
        clearStateCommand != null -> clearStateCommand
        clearKeychainCommand != null -> clearKeychainCommand
        runFlowCommand != null -> runFlowCommand
        setLocationCommand != null -> setLocationCommand
        setOrientationCommand != null -> setOrientationCommand
        assertScreenshotCommand != null -> assertScreenshotCommand
        repeatCommand != null -> repeatCommand
        copyTextCommand != null -> copyTextCommand
        setClipboardCommand != null -> setClipboardCommand
        pasteTextCommand != null -> pasteTextCommand
        defineVariablesCommand != null -> defineVariablesCommand
        runScriptCommand != null -> runScriptCommand
        waitForAnimationToEndCommand != null -> waitForAnimationToEndCommand
        evalScriptCommand != null -> evalScriptCommand
        scrollUntilVisible != null -> scrollUntilVisible
        travelCommand != null -> travelCommand
        startRecordingCommand != null -> startRecordingCommand
        stopRecordingCommand != null -> stopRecordingCommand
        addMediaCommand != null -> addMediaCommand
        setAirplaneModeCommand != null -> setAirplaneModeCommand
        toggleAirplaneModeCommand != null -> toggleAirplaneModeCommand
        retryCommand != null -> retryCommand
        else -> null
    }

    fun elementSelector(): ElementSelector? {
        return when {
            tapOnElement?.selector != null -> tapOnElement.selector
            swipeCommand?.elementSelector != null -> swipeCommand.elementSelector
            copyTextCommand?.selector != null -> copyTextCommand.selector
            assertConditionCommand?.condition?.visible != null -> assertConditionCommand.condition.visible
            assertConditionCommand?.condition?.notVisible != null -> assertConditionCommand.condition.notVisible
            scrollUntilVisible?.selector != null -> scrollUntilVisible.selector
            else -> null
        }
    }

    fun evaluateScripts(jsEngine: JsEngine): MaestroCommand {
        return asCommand()
            ?.let { MaestroCommand(it.evaluateScripts(jsEngine)) }
            ?: MaestroCommand()
    }

    fun description(): String {
        return asCommand()?.description() ?: "No op"
    }

    override fun toString(): String =
        asCommand()?.let { command ->
            val argName = command::class.simpleName?.replaceFirstChar(Char::lowercaseChar) ?: "command"
            "MaestroCommand($argName=$command)"
        } ?: "MaestroCommand()"

    fun yamlString(): String {
        return asCommand()?.yamlString() ?: "UNKNOWN"
    }
}
