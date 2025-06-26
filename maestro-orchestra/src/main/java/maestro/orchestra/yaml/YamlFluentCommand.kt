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
import maestro.KeyCode
import maestro.Point
import maestro.TapRepeat
import maestro.orchestra.AddMediaCommand
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.AssertNoDefectsWithAICommand
import maestro.orchestra.AssertWithAICommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearKeychainCommand
import maestro.orchestra.ClearStateCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import maestro.orchestra.CopyTextFromCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.ElementTrait
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.EvalScriptCommand
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
import maestro.orchestra.SourceLocation
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
    val assertWithAI: YamlAssertWithAI? = null,
    val extractTextWithAI: YamlExtractTextWithAI? = null,
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
    val launchApp: YamlLaunchApp? = null,
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
    val repeat: YamlRepeatCommand? = null,
    val copyTextFrom: YamlElementSelectorUnion? = null,
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
            launchApp != null -> listOf(launchApp(launchApp, appId, flowPath))
            tapOn != null -> listOf(tapCommand(tapOn, flowPath))
            longPressOn != null -> listOf(tapCommand(longPressOn, flowPath, longPress = true))
            assertVisible != null -> listOf(
                maestroCommand(
                    AssertConditionCommand(
                        condition = Condition(
                            visible = toElementSelector(assertVisible),
                        ),
                        label = (assertVisible as? YamlElementSelector)?.label,
                        optional = (assertVisible as? YamlElementSelector)?.optional ?: false,
                    ),
                    flowPath
                )
            )

            assertNotVisible != null -> listOf(
                maestroCommand(
                    AssertConditionCommand(
                        condition = Condition(
                            notVisible = toElementSelector(assertNotVisible),
                        ),
                        label = (assertNotVisible as? YamlElementSelector)?.label,
                        optional = (assertNotVisible as? YamlElementSelector)?.optional ?: false,
                    ),
                    flowPath
                )
            )

            assertTrue != null -> listOf(
                maestroCommand(
                    AssertConditionCommand(
                        Condition(
                            scriptCondition = assertTrue.condition,
                        ),
                        label = assertTrue.label,
                        optional = assertTrue.optional,
                    ),
                    flowPath
                )
            )

            assertNoDefectsWithAI != null -> listOf(
                maestroCommand(
                    AssertNoDefectsWithAICommand(
                        optional = assertNoDefectsWithAI.optional,
                        label = assertNoDefectsWithAI.label,
                    ),
                    flowPath
                )
            )

            assertWithAI != null -> listOf(
                maestroCommand(
                    AssertWithAICommand(
                        assertion = assertWithAI.assertion,
                        optional = assertWithAI.optional,
                        label = assertWithAI.label,
                    ),
                    flowPath
                )
            )

            extractTextWithAI != null -> listOf(
                maestroCommand(
                    ExtractTextWithAICommand(
                        query = extractTextWithAI.query,
                        outputVariable = extractTextWithAI.outputVariable,
                        optional = extractTextWithAI.optional,
                        label = extractTextWithAI.label,
                    ),
                    flowPath
                )
            )

            addMedia != null -> listOf(
                maestroCommand(
                    addMediaCommand(addMedia, flowPath),
                    flowPath,
                )
            )

            inputText != null -> listOf(
                maestroCommand(
                    InputTextCommand(
                        text = inputText.text,
                        label = inputText.label,
                        optional = inputText.optional
                    ),
                    flowPath
                )
            )

            inputRandomText != null -> listOf(
                maestroCommand(
                    InputRandomCommand(
                        inputType = InputRandomType.TEXT,
                        length = inputRandomText.length,
                        label = inputRandomText.label,
                        optional = inputRandomText.optional
                    ),
                    flowPath
                )
            )

            inputRandomNumber != null -> listOf(
                maestroCommand(
                    InputRandomCommand(
                        inputType = InputRandomType.NUMBER,
                        length = inputRandomNumber.length,
                        label = inputRandomNumber.label,
                        optional = inputRandomNumber.optional
                    ),
                    flowPath
                )
            )

            inputRandomEmail != null -> listOf(
                maestroCommand(
                    InputRandomCommand(
                        inputType = InputRandomType.TEXT_EMAIL_ADDRESS,
                        label = inputRandomEmail.label,
                        optional = inputRandomEmail.optional
                    ),
                    flowPath
                )
            )

            inputRandomPersonName != null -> listOf(
                maestroCommand(
                    InputRandomCommand(
                        inputType = InputRandomType.TEXT_PERSON_NAME,
                        label = inputRandomPersonName.label,
                        optional = inputRandomPersonName.optional
                    ),
                    flowPath
                )
            )

            swipe != null -> listOf(swipeCommand(swipe, flowPath))
            openLink != null -> {
                listOf(
                    maestroCommand(
                        OpenLinkCommand(
                            link = openLink.link,
                            autoVerify = openLink.autoVerify,
                            browser = openLink.browser,
                            label = openLink.label,
                            optional = openLink.optional
                        ), flowPath
                    )
                )
            }

            pressKey != null -> listOf(
                maestroCommand(
                    PressKeyCommand(
                        code = KeyCode.getByName(pressKey.key) ?: throw SyntaxError("Unknown key name: $pressKey"),
                        label = pressKey.label,
                        optional = pressKey.optional
                    ),
                    flowPath
                )
            )

            eraseText != null -> listOf(eraseCommand(eraseText, flowPath))
            action != null -> listOf(
                when (action) {
                    "back" -> maestroCommand(BackPressCommand(), flowPath)
                    "hideKeyboard" -> maestroCommand(HideKeyboardCommand(), flowPath)
                    "scroll" -> maestroCommand(ScrollCommand(), flowPath)
                    "clearKeychain" -> maestroCommand(ClearKeychainCommand(), flowPath)
                    "pasteText" -> maestroCommand(PasteTextCommand(), flowPath)
                    else -> error("Unknown navigation target: $action")
                }
            )

            back != null -> listOf(maestroCommand(
                BackPressCommand(label = back.label, optional = back.optional),
                flowPath
            ))
            clearKeychain != null -> listOf(
                maestroCommand(
                    ClearKeychainCommand(
                        label = clearKeychain.label,
                        optional = clearKeychain.optional
                    ),
                    flowPath
                )
            )

            hideKeyboard != null -> listOf(
                maestroCommand(
                    HideKeyboardCommand(
                        label = hideKeyboard.label,
                        optional = hideKeyboard.optional
                    ),
                    flowPath
                )
            )

            pasteText != null -> listOf(
                maestroCommand(
                    PasteTextCommand(
                        label = pasteText.label,
                        optional = pasteText.optional
                    ),
                    flowPath
                )
            )

            scroll != null -> listOf(maestroCommand(
                ScrollCommand(label = scroll.label, optional = scroll.optional),
                flowPath
            ))
            takeScreenshot != null -> listOf(
                maestroCommand(
                    TakeScreenshotCommand(
                        path = takeScreenshot.path,
                        label = takeScreenshot.label,
                        optional = takeScreenshot.optional
                    ),
                    flowPath
                )
            )

            extendedWaitUntil != null -> listOf(extendedWait(extendedWaitUntil, flowPath))
            stopApp != null -> listOf(
                maestroCommand(
                    StopAppCommand(
                        appId = stopApp.appId ?: appId,
                        label = stopApp.label,
                        optional = stopApp.optional,
                    ),
                    flowPath
                )
            )

            killApp != null -> listOf(
                maestroCommand(
                    KillAppCommand(
                        appId = killApp.appId ?: appId,
                        label = killApp.label,
                        optional = killApp.optional,
                    ),
                    flowPath
                )
            )

            clearState != null -> listOf(
                maestroCommand(
                    ClearStateCommand(
                        appId = clearState.appId ?: appId,
                        label = clearState.label,
                        optional = clearState.optional,
                    ),
                    flowPath
                )
            )

            runFlow != null -> listOf(runFlowCommand(appId, flowPath, runFlow))
            setLocation != null -> listOf(
                maestroCommand(
                    SetLocationCommand(
                        latitude = setLocation.latitude,
                        longitude = setLocation.longitude,
                        label = setLocation.label,
                        optional = setLocation.optional,
                    ),
                    flowPath
                )
            )

            repeat != null -> listOf(
                repeatCommand(repeat, flowPath, appId)
            )

            retry != null -> listOf(
                retryCommand(retry, flowPath, appId)
            )

            copyTextFrom != null -> listOf(copyTextFromCommand(copyTextFrom, flowPath))
            runScript != null -> listOf(
                maestroCommand(
                    RunScriptCommand(
                        script = resolvePath(flowPath, runScript.file)
                            .readText(),
                        env = runScript.env,
                        sourceDescription = runScript.file,
                        condition = runScript.`when`?.toCondition(),
                        label = runScript.label,
                        optional = runScript.optional,
                    ),
                    flowPath
                )
            )

            waitForAnimationToEnd != null -> listOf(
                maestroCommand(
                    WaitForAnimationToEndCommand(
                        timeout = waitForAnimationToEnd.timeout,
                        label = waitForAnimationToEnd.label,
                        optional = waitForAnimationToEnd.optional,
                    ),
                    flowPath
                )
            )

            evalScript != null -> listOf(
                maestroCommand(
                    EvalScriptCommand(
                        scriptString = evalScript.script,
                        label = evalScript.label,
                        optional = evalScript.optional,
                    ),
                    flowPath
                )
            )

            scrollUntilVisible != null -> listOf(scrollUntilVisibleCommand(scrollUntilVisible, flowPath))
            travel != null -> listOf(travelCommand(travel, flowPath))
            startRecording != null -> listOf(
                maestroCommand(
                    StartRecordingCommand(
                        startRecording.path,
                        startRecording.label,
                        startRecording.optional
                    ),
                    flowPath
                )
            )

            stopRecording != null -> listOf(
                maestroCommand(
                    StopRecordingCommand(
                        stopRecording.label,
                        stopRecording.optional
                    ),
                    flowPath
                )
            )

            doubleTapOn != null -> {
                val yamlDelay = (doubleTapOn as? YamlElementSelector)?.delay?.toLong()
                val delay =
                    if (yamlDelay != null && yamlDelay >= 0) yamlDelay else TapOnElementCommand.DEFAULT_REPEAT_DELAY
                val tapRepeat = TapRepeat(2, delay)
                listOf(tapCommand(doubleTapOn, flowPath, tapRepeat = tapRepeat))
            }

            setAirplaneMode != null -> listOf(
                maestroCommand(
                    SetAirplaneModeCommand(
                        setAirplaneMode.value,
                        setAirplaneMode.label,
                        setAirplaneMode.optional
                    ),
                    flowPath
                )
            )

            toggleAirplaneMode != null -> listOf(
                maestroCommand(
                    ToggleAirplaneModeCommand(
                        toggleAirplaneMode.label,
                        toggleAirplaneMode.optional
                    ),
                    flowPath
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

        return maestroCommand(
            RunFlowCommand(
                commands = commands,
                condition = runFlow.`when`?.toCondition(),
                sourceDescription = runFlow.file,
                config = config,
                label = runFlow.label,
                optional = runFlow.optional,
            ),
            flowPath
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

        return maestroCommand(
            RetryCommand(
                maxRetries = maxRetries,
                commands = commands,
                label = retry.label,
                optional = retry.optional,
                config = config
            ),
            flowPath
        )
    }

    private fun travelCommand(command: YamlTravelCommand, flowPath: Path): MaestroCommand {
        return maestroCommand(
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
            ),
            flowPath
        )
    }

    private fun repeatCommand(repeat: YamlRepeatCommand, flowPath: Path, appId: String) = maestroCommand(
        RepeatCommand(
            times = repeat.times,
            condition = repeat.`while`?.toCondition(),
            commands = repeat.commands
                .flatMap { it.toCommands(flowPath, appId) },
            label = repeat.label,
            optional = repeat.optional,
        ),
        flowPath
    )

    private fun eraseCommand(eraseText: YamlEraseText, flowPath: Path): MaestroCommand {
        return if (eraseText.charactersToErase != null) {
            maestroCommand(
                EraseTextCommand(
                    charactersToErase = eraseText.charactersToErase,
                    label = eraseText.label,
                    optional = eraseText.optional
                ),
                flowPath
            )
        } else {
            maestroCommand(
                EraseTextCommand(
                    charactersToErase = null,
                    label = eraseText.label,
                    optional = eraseText.optional
                ),
                flowPath
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

        val resolvedPath = if (path.isAbsolute) {
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

    private fun extendedWait(command: YamlExtendedWaitUntil, flowPath: Path): MaestroCommand {
        if (command.visible == null && command.notVisible == null) {
            throw SyntaxError("extendedWaitUntil expects either `visible` or `notVisible` to be provided")
        }

        val condition = Condition(
            visible = command.visible?.let { toElementSelector(it) },
            notVisible = command.notVisible?.let { toElementSelector(it) },
        )

        return maestroCommand(
            AssertConditionCommand(
                condition = condition,
                timeout = command.timeout,
                label = command.label,
                optional = command.optional,
            ),
            flowPath
        )
    }

    private fun launchApp(command: YamlLaunchApp, appId: String, flowPath: Path): MaestroCommand {
        return maestroCommand(
            LaunchAppCommand(
                appId = command.appId ?: appId,
                clearState = command.clearState,
                clearKeychain = command.clearKeychain,
                stopApp = command.stopApp,
                permissions = command.permissions,
                launchArguments = command.arguments,
                label = command.label,
                optional = command.optional,
            ),
            flowPath
        )
    }

    private fun tapCommand(
        tapOn: YamlElementSelectorUnion,
        flowPath: Path,
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
            maestroCommand(
                TapOnPointV2Command(
                    point = point,
                    retryIfNoChange = retryIfNoChange,
                    longPress = longPress,
                    repeat = repeat,
                    waitToSettleTimeoutMs = waitToSettleTimeoutMs,
                    label = label,
                    optional = optional,
                ),
                flowPath
            )
        } else {
            maestroCommand(
                TapOnElementCommand(
                    selector = toElementSelector(tapOn),
                    retryIfNoChange = retryIfNoChange,
                    waitUntilVisible = waitUntilVisible,
                    longPress = longPress,
                    repeat = repeat,
                    waitToSettleTimeoutMs = waitToSettleTimeoutMs,
                    label = label,
                    optional = optional,
                ),
                flowPath
            )
        }
    }

    private fun swipeCommand(swipe: YamlSwipe, flowPath: Path): MaestroCommand {
        when (swipe) {
            is YamlSwipeDirection -> return maestroCommand(
                SwipeCommand(
                    direction = swipe.direction,
                    duration = swipe.duration,
                    label = swipe.label,
                    optional = swipe.optional,
                    waitToSettleTimeoutMs = swipe.waitToSettleTimeoutMs
                ),
                flowPath
            )

            is YamlCoordinateSwipe -> {
                val start = swipe.start
                val end = swipe.end
                val startPoint: Point?
                val endPoint: Point?

                val startPoints = start.split(",")
                    .map {
                        it.trim().toInt()
                    }
                startPoint = Point(startPoints[0], startPoints[1])

                val endPoints = end.split(",")
                    .map {
                        it.trim().toInt()
                    }
                endPoint = Point(endPoints[0], endPoints[1])

                return maestroCommand(
                    SwipeCommand(
                        startPoint = startPoint,
                        endPoint = endPoint,
                        duration = swipe.duration,
                        label = swipe.label,
                        optional = swipe.optional,
                        waitToSettleTimeoutMs = swipe.waitToSettleTimeoutMs
                    ),
                    flowPath
                )
            }

            is YamlRelativeCoordinateSwipe -> {
                return maestroCommand(
                    SwipeCommand(
                        startRelative = swipe.start,
                        endRelative = swipe.end,
                        duration = swipe.duration,
                        label = swipe.label,
                        optional = swipe.optional,
                        waitToSettleTimeoutMs = swipe.waitToSettleTimeoutMs
                    ),
                    flowPath
                )
            }

            is YamlSwipeElement -> return swipeElementCommand(swipe, flowPath)
            else -> {
                throw IllegalStateException(
                    "Provide swipe direction UP, DOWN, RIGHT OR LEFT or by giving explicit " +
                            "start and end coordinates."
                )
            }
        }
    }

    private fun swipeElementCommand(swipeElement: YamlSwipeElement, flowPath: Path): MaestroCommand {
        return maestroCommand(
            SwipeCommand(
                direction = swipeElement.direction,
                elementSelector = toElementSelector(swipeElement.from),
                duration = swipeElement.duration,
                label = swipeElement.label,
                optional = swipeElement.optional,
                waitToSettleTimeoutMs = swipeElement.waitToSettleTimeoutMs
            ),
            flowPath
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
        copyText: YamlElementSelectorUnion,
        flowPath: Path
    ): MaestroCommand {
        return if (copyText is StringElementSelector) {
            maestroCommand(
                CopyTextFromCommand(
                    selector = toElementSelector(copyText)
                ),
                flowPath
            )
        } else {
            maestroCommand(
                CopyTextFromCommand(
                    selector = toElementSelector(copyText),
                    label = (copyText as? YamlElementSelector)?.label,
                    optional = (copyText as? YamlElementSelector)?.optional ?: false
                ),
                flowPath
            )
        }
    }

    private fun scrollUntilVisibleCommand(yaml: YamlScrollUntilVisible, flowPath: Path): MaestroCommand {
        val visibility =
            if (yaml.visibilityPercentage < 0) 0 else if (yaml.visibilityPercentage > 100) 100 else yaml.visibilityPercentage
        return maestroCommand(
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
            ),
            flowPath
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

    private fun maestroCommand(command: Command, flowPath: Path): MaestroCommand {
        val sourceLocation = SourceLocation(
            file = flowPath.toAbsolutePath().toString(),
            line = _location.lineNr,
            column = _location.columnNr,
        )
        return MaestroCommand(command, sourceLocation)
    }
}
