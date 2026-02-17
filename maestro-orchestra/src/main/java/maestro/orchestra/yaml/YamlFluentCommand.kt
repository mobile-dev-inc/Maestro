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

package maestro.orchestra.yaml

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.core.JsonLocation
import maestro.DeviceOrientation
import maestro.KeyCode
import maestro.Point
import maestro.TapRepeat
import maestro.orchestra.AddMediaCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.AssertNoDefectsWithAICommand
import maestro.orchestra.AssertVisualCommand
import maestro.orchestra.AssertScreenshotCommand
import maestro.orchestra.AssertWithAICommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearKeychainCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.Condition
import maestro.orchestra.CopyTextFromCommand
import maestro.orchestra.SetClipboardCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.ElementTrait
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.EvalScriptCommand
import maestro.orchestra.ExtractComponentWithAICommand
import maestro.orchestra.ExtractPointWithAICommand
import maestro.orchestra.ExtractTextWithAICommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputRandomCommand
import maestro.orchestra.InputRandomType
import maestro.orchestra.InputTextCommand
import maestro.orchestra.KillAppCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PasteTextCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.RepeatCommand
import maestro.orchestra.RetryCommand
import maestro.orchestra.RunFlowCommand
import maestro.orchestra.RunScriptCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.SetAirplaneModeCommand
import maestro.orchestra.SetLocationCommand
import maestro.orchestra.SetOrientationCommand
import maestro.orchestra.SetPermissionsCommand
import maestro.orchestra.StartRecordingCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.StopRecordingCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.ToggleAirplaneModeCommand
import maestro.orchestra.TravelCommand
import maestro.orchestra.WaitForAnimationToEndCommand
import maestro.orchestra.error.InvalidFlowFile
import maestro.orchestra.error.MediaFileNotFound
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.util.Env.withEnv
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

class ToCommandsException(
    override val cause: Throwable,
    val location: JsonLocation,
) : RuntimeException(cause)

data class YamlFluentCommand(
    val tapOn: YamlElementSelectorUnion? = null,
    val doubleTapOn: YamlElementSelectorUnion? = null,
    val longPressOn: YamlElementSelectorUnion? = null,
    val assertVisible: YamlElementSelectorUnion? = null,
    val assertNotVisible: YamlElementSelectorUnion? = null,
    val assertTrue: YamlAssertTrue? = null,
    val assertNoDefectsWithAI: YamlAssertNoDefectsWithAI? = null,
    val assertVisual: YamlAssertVisual? = null,
    val assertScreenshot: YamlAssertScreenshot? = null,
    val assertWithAI: YamlAssertWithAI? = null,
    val extractTextWithAI: YamlExtractTextWithAI? = null,
    val extractPointWithAI: YamlExtractPointWithAI? = null,
    val extractComponentWithAI: YamlExtractComponentWithAI? = null,
    val back: YamlActionBack? = null,
    val clearKeychain: YamlActionClearKeychain? = null,
    val hideKeyboard: YamlActionHideKeyboard? = null,
    val pasteText: YamlActionPasteText? = null,
    val scroll: YamlActionScroll? = null,
    val inputText: YamlInputText? = null,
    val inputRandomText: YamlInputRandomText? = null,
    val inputRandomNumber: YamlInputRandomNumber? = null,
    val inputRandomEmail: YamlInputRandomEmail? = null,
    val inputRandomPersonName: YamlInputRandomPersonName? = null,
    val inputRandomCityName: YamlInputRandomCityName? = null,
    val inputRandomCountryName: YamlInputRandomCountryName? = null,
    val inputRandomColorName: YamlInputRandomColorName? = null,
    val launchApp: YamlLaunchApp? = null,
    val setPermissions: YamlSetPermissions? = null,
    val swipe: YamlSwipe? = null,
    val openLink: YamlOpenLink? = null,
    val openBrowser: String? = null,
    val pressKey: YamlPressKey? = null,
    val eraseText: YamlEraseText? = null,
    val action: String? = null,
    val takeScreenshot: YamlTakeScreenshot? = null,
    val extendedWaitUntil: YamlExtendedWaitUntil? = null,
    val stopApp: YamlStopApp? = null,
    val killApp: YamlKillApp? = null,
    val clearState: YamlClearState? = null,
    val runFlow: YamlRunFlow? = null,
    val setLocation: YamlSetLocation? = null,
    val setOrientation: YamlSetOrientation? = null,
    val repeat: YamlRepeatCommand? = null,
    val copyTextFrom: YamlElementSelectorUnion? = null,
    val setClipboard: YamlSetClipboard? = null,
    val runScript: YamlRunScript? = null,
    val waitForAnimationToEnd: YamlWaitForAnimationToEndCommand? = null,
    val evalScript: YamlEvalScript? = null,
    val scrollUntilVisible: YamlScrollUntilVisible? = null,
    val travel: YamlTravelCommand? = null,
    val startRecording: YamlStartRecording? = null,
    val stopRecording: YamlStopRecording? = null,
    val addMedia: YamlAddMedia? = null,
    val setAirplaneMode: YamlSetAirplaneMode? = null,
    val toggleAirplaneMode: YamlToggleAirplaneMode? = null,
    val retry: YamlRetryCommand? = null,
    @JsonIgnore val _location: JsonLocation,
) {

    fun toCommands(flowPath: Path, appId: String): List<MaestroCommand> {
        return try {
            _toCommands(flowPath, appId)
        } catch (e: Throwable) {
            throw ToCommandsException(e, _location)
        }
    }

    @SuppressWarnings("ComplexMethod")
    private fun _toCommands(flowPath: Path, appId: String): List<MaestroCommand> {
        return when {
            launchApp != null -> listOf(launchApp(launchApp, appId))
            setPermissions != null -> listOf(setPermissions(command = setPermissions, appId))
            tapOn != null -> listOf(tapCommand(tapOn))
            longPressOn != null -> listOf(tapCommand(longPressOn, longPress = true))
            assertVisible != null -> listOf(
                MaestroCommand(
                    AssertConditionCommand(
                        condition = Condition(
                            visible = toElementSelector(assertVisible),
                        ),
                        label = (assertVisible as? YamlElementSelector)?.label,
                        optional = (assertVisible as? YamlElementSelector)?.optional ?: false,
                    )
                )
            )

            assertNotVisible != null -> listOf(
                MaestroCommand(
                    AssertConditionCommand(
                        condition = Condition(
                            notVisible = toElementSelector(assertNotVisible),
                        ),
                        label = (assertNotVisible as? YamlElementSelector)?.label,
                        optional = (assertNotVisible as? YamlElementSelector)?.optional ?: false,
                    )
                )
            )

            assertTrue != null -> listOf(
                MaestroCommand(
                    AssertConditionCommand(
                        Condition(
                            scriptCondition = assertTrue.condition,
                        ),
                        label = assertTrue.label,
                        optional = assertTrue.optional,
                    )
                )
            )

            assertVisual != null -> listOf(
                MaestroCommand(
                    AssertVisualCommand(
                        baseline = assertVisual.baseline,
                        thresholdPercentage = assertVisual.thresholdPercentage,
                        optional = assertVisual.optional,
                        label = assertVisual.label
                    )
                )
            )

            assertNoDefectsWithAI != null -> listOf(
                MaestroCommand(
                    AssertNoDefectsWithAICommand(
                        optional = assertNoDefectsWithAI.optional,
                        label = assertNoDefectsWithAI.label,
                    )
                )
            )

            assertWithAI != null -> listOf(
                MaestroCommand(
                    AssertWithAICommand(
                        assertion = assertWithAI.assertion,
                        optional = assertWithAI.optional,
                        label = assertWithAI.label,
                    )
                )
            )

            extractTextWithAI != null -> listOf(
                MaestroCommand(
                    ExtractTextWithAICommand(
                        query = extractTextWithAI.query,
                        outputVariable = extractTextWithAI.outputVariable,
                        optional = extractTextWithAI.optional,
                        label = extractTextWithAI.label,
                    )
                )
            )

            extractPointWithAI != null -> listOf(
                MaestroCommand(
                    ExtractPointWithAICommand(
                        query = extractPointWithAI.query,
                        outputVariable = extractPointWithAI.outputVariable,
                        passes = extractPointWithAI.passes,
                        optional = extractPointWithAI.optional,
                        label = extractPointWithAI.label,
                    )
                )
            )

            extractComponentWithAI != null -> {
                listOf(
                    MaestroCommand(
                        ExtractComponentWithAICommand(
                            imagePath = extractComponentWithAI.image,
                            outputVariable = extractComponentWithAI.outputVariable,
                            optional = extractComponentWithAI.optional,
                            label = extractComponentWithAI.label,
                        )
                    )
                )
            }

            assertScreenshot != null -> listOf(
                MaestroCommand(
                    AssertScreenshotCommand(
                        path = assertScreenshot.path,
                        thresholdPercentage = assertScreenshot.thresholdPercentage,
                        cropOn = assertScreenshot.cropOn?.let { toElementSelector(it) },
                        optional = assertScreenshot.optional,
                        label = assertScreenshot.label
                    )
                )
            )
            addMedia != null -> listOf(
                MaestroCommand(
                    addMediaCommand = addMediaCommand(addMedia, flowPath)
                )
            )
            inputText != null -> listOf(MaestroCommand(InputTextCommand(text = inputText.text, label = inputText.label, optional = inputText.optional)))
            inputRandomText != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.TEXT, length = inputRandomText.length, label = inputRandomText.label, optional = inputRandomText.optional)))
            inputRandomNumber != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.NUMBER, length = inputRandomNumber.length, label = inputRandomNumber.label, optional = inputRandomNumber.optional)))
            inputRandomEmail != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.TEXT_EMAIL_ADDRESS, label = inputRandomEmail.label, optional = inputRandomEmail.optional)))
            inputRandomPersonName != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.TEXT_PERSON_NAME, label = inputRandomPersonName.label, optional = inputRandomPersonName.optional)))
            inputRandomCityName != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.TEXT_CITY_NAME, label = inputRandomCityName.label, optional = inputRandomCityName.optional)))
            inputRandomCountryName != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.TEXT_COUNTRY_NAME, label = inputRandomCountryName.label, optional = inputRandomCountryName.optional)))
            inputRandomColorName != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.TEXT_COLOR, label = inputRandomColorName.label, optional = inputRandomColorName.optional)))

            swipe != null -> listOf(swipeCommand(swipe))
            openLink != null -> listOf(
                MaestroCommand(
                    OpenLinkCommand(
                        link = openLink.link,
                        autoVerify = openLink.autoVerify,
                        browser = openLink.browser,
                        label = openLink.label,
                        optional = openLink.optional
                    )
                )
            )

            pressKey != null -> listOf(
                MaestroCommand(
                    PressKeyCommand(
                        code = KeyCode.getByName(pressKey.key) ?: throw SyntaxError("Unknown key name: $pressKey"),
                        label = pressKey.label,
                        optional = pressKey.optional
                    )
                )
            )

            eraseText != null -> listOf(eraseCommand(eraseText))
            action != null -> listOf(
                when (action) {
                    "back" -> MaestroCommand(BackPressCommand())
                    "hideKeyboard" -> MaestroCommand(HideKeyboardCommand())
                    "scroll" -> MaestroCommand(ScrollCommand())
                    "clearKeychain" -> MaestroCommand(ClearKeychainCommand())
                    "pasteText" -> MaestroCommand(PasteTextCommand())
                    else -> error("Unknown navigation target: $action")
                }
            )

            back != null -> listOf(MaestroCommand(BackPressCommand(label = back.label, optional = back.optional)))
            clearKeychain != null -> listOf(
                MaestroCommand(
                    ClearKeychainCommand(
                        label = clearKeychain.label,
                        optional = clearKeychain.optional
                    )
                )
            )

            hideKeyboard != null -> listOf(
                MaestroCommand(
                    HideKeyboardCommand(
                        label = hideKeyboard.label,
                        optional = hideKeyboard.optional
                    )
                )
            )

            pasteText != null -> listOf(
                MaestroCommand(
                    PasteTextCommand(
                        label = pasteText.label,
                        optional = pasteText.optional
                    )
                )
            )

            scroll != null -> listOf(MaestroCommand(ScrollCommand(label = scroll.label, optional = scroll.optional)))
            takeScreenshot != null -> listOf(
                MaestroCommand(
                    TakeScreenshotCommand(
                        path = takeScreenshot.path,
                        label = takeScreenshot.label,
                        optional = takeScreenshot.optional,
                        cropOn = takeScreenshot.cropOn?.let { toElementSelector(selectorUnion = it) },
                    )
                )
            )

            extendedWaitUntil != null -> listOf(extendedWait(extendedWaitUntil))
            stopApp != null -> listOf(
                MaestroCommand(
                    StopAppCommand(
                        appId = stopApp.appId ?: appId,
                        label = stopApp.label,
                        optional = stopApp.optional,
                    )
                )
            )

            killApp != null -> listOf(
                MaestroCommand(
                    KillAppCommand(
                        appId = killApp.appId ?: appId,
                        label = killApp.label,
                        optional = killApp.optional,
                    )
                )
            )

            clearState != null -> listOf(
                MaestroCommand(
                    ClearStateCommand(
                        appId = clearState.appId ?: appId,
                        label = clearState.label,
                        optional = clearState.optional,
                    )
                )
            )

            runFlow != null -> listOf(runFlowCommand(appId, flowPath, runFlow))
            setLocation != null -> listOf(
                MaestroCommand(
                    SetLocationCommand(
                        latitude = setLocation.latitude,
                        longitude = setLocation.longitude,
                        label = setLocation.label,
                        optional = setLocation.optional,
                    )
                )
            )

            setOrientation != null -> listOf(
                MaestroCommand(
                    SetOrientationCommand(
                        orientation = DeviceOrientation.getByName(setOrientation.orientation) ?: throw SyntaxError("Unknown orientation: $setOrientation"),
                        label = setOrientation.label,
                        optional = setOrientation.optional,
                    )
                )
            )
            
            repeat != null -> listOf(
                repeatCommand(repeat, flowPath, appId)
            )

            retry != null -> listOf(
                retryCommand(retry, flowPath, appId)
            )

            copyTextFrom != null -> listOf(copyTextFromCommand(copyTextFrom))
            setClipboard != null -> listOf(
                MaestroCommand(
                    SetClipboardCommand(
                        text = setClipboard.text,
                        label = setClipboard.label,
                        optional = setClipboard.optional
                    )
                )
            )
            runScript != null -> {
                val scriptPath = resolvePath(flowPath, runScript.file)
                listOf(
                    MaestroCommand(
                        RunScriptCommand(
                            script = scriptPath.readText(),
                            env = runScript.env,
                            sourceDescription = scriptPath.toString(),
                            condition = runScript.`when`?.toCondition(),
                            label = runScript.label,
                            optional = runScript.optional,
                        )
                    )
                )
            }

            waitForAnimationToEnd != null -> listOf(
                MaestroCommand(
                    WaitForAnimationToEndCommand(
                        timeout = waitForAnimationToEnd.timeout,
                        label = waitForAnimationToEnd.label,
                        optional = waitForAnimationToEnd.optional,
                    )
                )
            )

            evalScript != null -> listOf(
                MaestroCommand(
                    EvalScriptCommand(
                        scriptString = evalScript.script,
                        label = evalScript.label,
                        optional = evalScript.optional,
                    )
                )
            )

            scrollUntilVisible != null -> listOf(scrollUntilVisibleCommand(scrollUntilVisible))
            travel != null -> listOf(travelCommand(travel))
            startRecording != null -> listOf(
                MaestroCommand(
                    StartRecordingCommand(
                        startRecording.path,
                        startRecording.label,
                        startRecording.optional
                    )
                )
            )

            stopRecording != null -> listOf(
                MaestroCommand(
                    StopRecordingCommand(
                        stopRecording.label,
                        stopRecording.optional
                    )
                )
            )

            doubleTapOn != null -> {
                val yamlDelay = (doubleTapOn as? YamlElementSelector)?.delay?.toLong()
                val delay =
                    if (yamlDelay != null && yamlDelay >= 0) yamlDelay else TapOnElementCommand.DEFAULT_REPEAT_DELAY
                val tapRepeat = TapRepeat(2, delay)
                listOf(tapCommand(doubleTapOn, tapRepeat = tapRepeat))
            }

            setAirplaneMode != null -> listOf(
                MaestroCommand(
                    SetAirplaneModeCommand(
                        setAirplaneMode.value,
                        setAirplaneMode.label,
                        setAirplaneMode.optional
                    )
                )
            )

            toggleAirplaneMode != null -> listOf(
                MaestroCommand(
                    ToggleAirplaneModeCommand(
                        toggleAirplaneMode.label,
                        toggleAirplaneMode.optional
                    )
                )
            )

            else -> throw SyntaxError("Invalid command: No mapping provided for $this")
        }
    }

    private fun addMediaCommand(addMedia: YamlAddMedia, flowPath: Path): AddMediaCommand {
        if (addMedia.files == null || addMedia.files.any { it == null }) {
            throw SyntaxError("Invalid addMedia command: media files cannot be empty")
        }

        val mediaPaths = addMedia.files.filterNotNull().map {
            val path = flowPath.fileSystem.getPath(it)

            val resolvedPath = if (path.isAbsolute) {
                path
            } else {
                flowPath.resolveSibling(path).toAbsolutePath()
            }
            if (!resolvedPath.exists()) {
                throw MediaFileNotFound("Media file at $path in flow file: $flowPath not found", path)
            }
            resolvedPath
        }
        val mediaAbsolutePathStrings = mediaPaths.mapNotNull { it.absolutePathString() }
        return AddMediaCommand(mediaAbsolutePathStrings, addMedia.label, addMedia.optional)
    }

    private fun runFlowCommand(
        appId: String,
        flowPath: Path,
        runFlow: YamlRunFlow
    ): MaestroCommand {
        if (runFlow.file == null && runFlow.commands == null) {
            throw SyntaxError("Invalid runFlow command: No file or commands provided")
        }

        if (runFlow.file != null && runFlow.commands != null) {
            throw SyntaxError("Invalid runFlow command: Can't provide both file and commands at the same time")
        }

        val commands = runFlow.commands
            ?.flatMap {
                it.toCommands(flowPath, appId)
                    .withEnv(runFlow.env)
            }
            ?: runFlow(flowPath, runFlow)

        val config = runFlow.file?.let {
            readConfig(flowPath, runFlow.file)
        }

        return MaestroCommand(
            RunFlowCommand(
                commands = commands,
                condition = runFlow.`when`?.toCondition(),
                sourceDescription = runFlow.file,
                config = config,
                label = runFlow.label,
                optional = runFlow.optional,
            )
        )
    }

    private fun retryCommand(retry: YamlRetryCommand, flowPath: Path, appId: String): MaestroCommand {
        if (retry.file == null && retry.commands == null) {
            throw SyntaxError("Invalid retry command: No file or commands provided")
        }

        if (retry.file != null && retry.commands != null) {
            throw SyntaxError("Invalid retry command: Can't provide both file and commands at the same time")
        }

        val commands = retry.commands
            ?.flatMap {
                it.toCommands(flowPath, appId)
                    .withEnv(retry.env)
            }
            ?: retry(flowPath, retry)

        val config = retry.file?.let {
            readConfig(flowPath, retry.file)
        }


        val maxRetries = retry.maxRetries ?: "1"

        return MaestroCommand(
            RetryCommand(
                maxRetries = maxRetries,
                commands = commands,
                sourceDescription = retry.file,
                label = retry.label,
                optional = retry.optional,
                config = config
            )
        )
    }

    private fun travelCommand(command: YamlTravelCommand): MaestroCommand {
        return MaestroCommand(
            TravelCommand(
                points = command.points
                    .map { point ->
                        val spitPoint = point.split(",")

                        if (spitPoint.size != 2) {
                            throw SyntaxError("Invalid travel point: $point")
                        }

                        val latitude =
                            spitPoint[0].toDoubleOrNull() ?: throw SyntaxError("Invalid travel point latitude: $point")
                        val longitude =
                            spitPoint[1].toDoubleOrNull() ?: throw SyntaxError("Invalid travel point longitude: $point")

                        TravelCommand.GeoPoint(
                            latitude = latitude.toString(),
                            longitude = longitude.toString(),
                        )
                    },
                speedMPS = command.speed,
                label = command.label,
                optional = command.optional,
            )
        )
    }

    private fun repeatCommand(repeat: YamlRepeatCommand, flowPath: Path, appId: String) = MaestroCommand(
        RepeatCommand(
            times = repeat.times,
            condition = repeat.`while`?.toCondition(),
            commands = repeat.commands
                .flatMap { it.toCommands(flowPath, appId) },
            label = repeat.label,
            optional = repeat.optional,
        )
    )

    private fun eraseCommand(eraseText: YamlEraseText): MaestroCommand {
        return if (eraseText.charactersToErase != null) {
            MaestroCommand(
                EraseTextCommand(
                    charactersToErase = eraseText.charactersToErase,
                    label = eraseText.label,
                    optional = eraseText.optional
                )
            )
        } else {
            MaestroCommand(
                EraseTextCommand(
                    charactersToErase = null,
                    label = eraseText.label,
                    optional = eraseText.optional
                )
            )
        }
    }

    fun getWatchFiles(flowPath: Path): List<Path> {
        return when {
            runFlow != null -> getRunFlowWatchFiles(flowPath, runFlow)
            else -> return emptyList()
        }
    }

    private fun getRunFlowWatchFiles(flowPath: Path, runFlow: YamlRunFlow): List<Path> {
        if (runFlow.file == null) {
            return emptyList()
        }

        val runFlowPath = resolvePath(flowPath, runFlow.file)
        return listOf(runFlowPath) + YamlCommandReader.getWatchFiles(runFlowPath)
    }

    private fun runFlow(flowPath: Path, command: YamlRunFlow): List<MaestroCommand> {
        if (command.file == null) {
            error("Invalid runFlow command: No file or commands provided")
        }

        val runFlowPath = resolvePath(flowPath, command.file)
        return YamlCommandReader.readCommands(runFlowPath)
            .withEnv(command.env)
    }

    private fun retry(flowPath: Path, command: YamlRetryCommand): List<MaestroCommand> {
        if (command.file == null) {
            error("Invalid runFlow command: No file or commands provided")
        }

        val retryFlowPath = resolvePath(flowPath, command.file)
        return YamlCommandReader.readCommands(retryFlowPath)
            .withEnv(command.env)
    }

    private fun readConfig(flowPath: Path, commandFile: String): MaestroConfig? {
        val runFlowPath = resolvePath(flowPath, commandFile)
        return YamlCommandReader.readConfig(runFlowPath).toCommand(runFlowPath).applyConfigurationCommand?.config
    }

    private fun resolvePath(flowPath: Path, requestedPath: String): Path {
        val path = flowPath.fileSystem.getPath(requestedPath)

        val fileParts = path.toString().split("/")
        val resolvedPath = if(fileParts[0] == "app") {
            val appRoot = flowPath.toString().split("/app/")[0]
            val packageName = fileParts[1]
            val scriptOrFlow = fileParts[2]

            val sharedFlow = fileParts.toTypedArray().sliceArray(3 until fileParts.size).joinToString()
            flowPath.fileSystem.getPath("$appRoot/app/packages/$packageName/maestro/shared/$scriptOrFlow/$sharedFlow")
        } else if (path.isAbsolute) {
            path
        } else {
            flowPath.resolveSibling(path).toAbsolutePath()
        }
        if (resolvedPath.equals(flowPath.toAbsolutePath())) {
            throw InvalidFlowFile(
                "Referenced Flow file can't be the same as the main Flow file: ${resolvedPath.toUri()}",
                resolvedPath
            )
        }
        if (!resolvedPath.exists()) {
            throw InvalidFlowFile("Flow file does not exist: ${resolvedPath.toUri()}", resolvedPath)
        }
        if (resolvedPath.isDirectory()) {
            throw InvalidFlowFile("Flow file can't be a directory: ${resolvedPath.toUri()}", resolvedPath)
        }
        return resolvedPath
    }

    private fun extendedWait(command: YamlExtendedWaitUntil): MaestroCommand {
        if (command.visible == null && command.notVisible == null) {
            throw SyntaxError("extendedWaitUntil expects either `visible` or `notVisible` to be provided")
        }

        val condition = Condition(
            visible = command.visible?.let { toElementSelector(it) },
            notVisible = command.notVisible?.let { toElementSelector(it) },
        )

        return MaestroCommand(
            AssertConditionCommand(
                condition = condition,
                timeout = command.timeout,
                label = command.label,
                optional = command.optional,
            )
        )
    }

    private fun launchApp(command: YamlLaunchApp, appId: String): MaestroCommand {
        return MaestroCommand(
            LaunchAppCommand(
                appId = command.appId ?: appId,
                clearState = command.clearState,
                clearKeychain = command.clearKeychain,
                stopApp = command.stopApp,
                permissions = command.permissions,
                launchArguments = command.arguments,
                label = command.label,
                optional = command.optional,
            )
        )
    }

    private fun setPermissions(command: YamlSetPermissions, appId: String): MaestroCommand {
        return MaestroCommand(
            SetPermissionsCommand(
                appId = command.appId ?: appId,
                permissions = command.permissions,
                label = command.label,
                optional = command.optional,
            )
        )
    }

    private fun tapCommand(
        tapOn: YamlElementSelectorUnion,
        longPress: Boolean = false,
        tapRepeat: TapRepeat? = null
    ): MaestroCommand {
        val retryIfNoChange = (tapOn as? YamlElementSelector)?.retryTapIfNoChange ?: false
        val waitUntilVisible = (tapOn as? YamlElementSelector)?.waitUntilVisible ?: false
        val point = (tapOn as? YamlElementSelector)?.point
        val label = (tapOn as? YamlElementSelector)?.label
        val optional = (tapOn as? YamlElementSelector)?.optional ?: false

        val delay = (tapOn as? YamlElementSelector)?.delay?.toLong()
        val repeat = tapRepeat ?: (tapOn as? YamlElementSelector)?.repeat?.let {
            val count = if (it <= 0) 1 else it
            val d = if (delay != null && delay >= 0) delay else TapOnElementCommand.DEFAULT_REPEAT_DELAY
            TapRepeat(count, d)
        }

        val waitToSettleTimeoutMs = (tapOn as? YamlElementSelector)?.waitToSettleTimeoutMs?.let {
            if (it > TapOnElementCommand.MAX_TIMEOUT_WAIT_TO_SETTLE_MS) TapOnElementCommand.MAX_TIMEOUT_WAIT_TO_SETTLE_MS
            else it
        }

        return if (point != null) {
            val elementSelector = toElementSelector(tapOn)
            
            // Check if we have both element selector and point - this means element-relative tap
            if (hasAnySelector(elementSelector)) {
                MaestroCommand(
                    command = TapOnElementCommand(
                        selector = elementSelector,
                        retryIfNoChange = retryIfNoChange,
                        waitUntilVisible = waitUntilVisible,
                        longPress = longPress,
                        repeat = repeat,
                        waitToSettleTimeoutMs = waitToSettleTimeoutMs,
                        label = label,
                        optional = optional,
                        relativePoint = point, // Parameter for element-relative coordinates
                    )
                )
            } else {
                // Pure point-based tap (screen coordinates)
                MaestroCommand(
                    TapOnPointV2Command(
                        point = point,
                        retryIfNoChange = retryIfNoChange,
                        longPress = longPress,
                        repeat = repeat,
                        waitToSettleTimeoutMs = waitToSettleTimeoutMs,
                        label = label,
                        optional = optional,
                    )
                )
            }
        } else {
            MaestroCommand(
                command = TapOnElementCommand(
                    selector = toElementSelector(tapOn),
                    retryIfNoChange = retryIfNoChange,
                    waitUntilVisible = waitUntilVisible,
                    longPress = longPress,
                    repeat = repeat,
                    waitToSettleTimeoutMs = waitToSettleTimeoutMs,
                    label = label,
                    optional = optional,
                )
            )
        }
    }

    private fun swipeCommand(swipe: YamlSwipe): MaestroCommand {
        when (swipe) {
            is YamlSwipeDirection -> return MaestroCommand(
                SwipeCommand(
                    direction = swipe.direction,
                    duration = swipe.duration,
                    label = swipe.label,
                    optional = swipe.optional,
                    waitToSettleTimeoutMs = swipe.waitToSettleTimeoutMs
                )
            )

            is YamlCoordinateSwipe -> {
                return MaestroCommand(
                    SwipeCommand(
                        start = swipe.start,
                        end = swipe.end,
                        duration = swipe.duration,
                        label = swipe.label,
                        optional = swipe.optional,
                        waitToSettleTimeoutMs = swipe.waitToSettleTimeoutMs
                    )
                )
            }

            is YamlRelativeCoordinateSwipe -> {
                return MaestroCommand(
                    SwipeCommand(
                        startRelative = swipe.start,
                        endRelative = swipe.end,
                        duration = swipe.duration,
                        label = swipe.label,
                        optional = swipe.optional,
                        waitToSettleTimeoutMs = swipe.waitToSettleTimeoutMs
                    )
                )
            }

            is YamlSwipeElement -> return swipeElementCommand(swipe)
            else -> {
                throw IllegalStateException(
                    "Provide swipe direction UP, DOWN, RIGHT OR LEFT or by giving explicit " +
                            "start and end coordinates."
                )
            }
        }
    }

    private fun swipeElementCommand(swipeElement: YamlSwipeElement): MaestroCommand {
        return MaestroCommand(
            swipeCommand = SwipeCommand(
                direction = swipeElement.direction,
                elementSelector = toElementSelector(swipeElement.from),
                duration = swipeElement.duration,
                label = swipeElement.label,
                optional = swipeElement.optional,
                waitToSettleTimeoutMs = swipeElement.waitToSettleTimeoutMs
            )
        )
    }

    private fun toElementSelector(selectorUnion: YamlElementSelectorUnion): ElementSelector {
        return if (selectorUnion is StringElementSelector) {
            ElementSelector(
                textRegex = selectorUnion.value,
            )
        } else if (selectorUnion is YamlElementSelector) {
            toElementSelector(selectorUnion)
        } else {
            throw IllegalStateException("Unknown selector type: $selectorUnion")
        }
    }

    private fun hasAnySelector(selector: ElementSelector): Boolean {
        return selector.textRegex != null ||
                selector.idRegex != null ||
                selector.size != null ||
                selector.below != null ||
                selector.above != null ||
                selector.leftOf != null ||
                selector.rightOf != null ||
                selector.containsChild != null ||
                selector.containsDescendants != null ||
                selector.traits != null ||
                selector.index != null ||
                selector.enabled != null ||
                selector.selected != null ||
                selector.checked != null ||
                selector.focused != null ||
                selector.childOf != null ||
                selector.css != null
    }

    private fun toElementSelector(selector: YamlElementSelector): ElementSelector {
        val size = if (selector.width != null || selector.height != null) {
            ElementSelector.SizeSelector(
                width = selector.width,
                height = selector.height,
                tolerance = selector.tolerance,
            )
        } else {
            null
        }

        return ElementSelector(
            textRegex = selector.text,
            idRegex = selector.id,
            size = size,
            optional = selector.optional ?: false,
            below = selector.below?.let { toElementSelector(it) },
            above = selector.above?.let { toElementSelector(it) },
            leftOf = selector.leftOf?.let { toElementSelector(it) },
            rightOf = selector.rightOf?.let { toElementSelector(it) },
            containsChild = selector.containsChild?.let { toElementSelector(it) },
            containsDescendants = selector.containsDescendants?.map { toElementSelector(it) },
            traits = selector.traits
                ?.split(" ")
                ?.map { ElementTrait.valueOf(it.replace('-', '_').uppercase()) },
            index = selector.index,
            enabled = selector.enabled,
            selected = selector.selected,
            checked = selector.checked,
            focused = selector.focused,
            childOf = selector.childOf?.let { toElementSelector(it) },
            css = selector.css,
        )
    }

    private fun copyTextFromCommand(
        copyText: YamlElementSelectorUnion
    ): MaestroCommand {
        return if (copyText is StringElementSelector) {
            MaestroCommand(
                CopyTextFromCommand(
                    selector = toElementSelector(copyText)
                )
            )
        } else {
            MaestroCommand(
                CopyTextFromCommand(
                    selector = toElementSelector(copyText),
                    label = (copyText as? YamlElementSelector)?.label,
                    optional = (copyText as? YamlElementSelector)?.optional ?: false
                )
            )
        }
    }

    private fun scrollUntilVisibleCommand(yaml: YamlScrollUntilVisible): MaestroCommand {
        val visibility =
            if (yaml.visibilityPercentage < 0) 0 else if (yaml.visibilityPercentage > 100) 100 else yaml.visibilityPercentage
        return MaestroCommand(
            ScrollUntilVisibleCommand(
                selector = toElementSelector(yaml.element),
                direction = yaml.direction,
                timeout = yaml.timeout,
                scrollDuration = yaml.speed,
                visibilityPercentage = visibility,
                centerElement = yaml.centerElement,
                label = yaml.label,
                optional = yaml.optional,
                originalSpeedValue = yaml.speed,
                waitToSettleTimeoutMs = yaml.waitToSettleTimeoutMs
            )
        )
    }

    private fun YamlCondition.toCondition(): Condition {
        return Condition(
            platform = platform,
            visible = visible?.let { toElementSelector(it) },
            notVisible = notVisible?.let { toElementSelector(it) },
            scriptCondition = `true`?.trim(),
            label = label
        )
    }
}
