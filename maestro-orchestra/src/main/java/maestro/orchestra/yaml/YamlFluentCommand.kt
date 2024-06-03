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

import com.fasterxml.jackson.annotation.JsonCreator
import maestro.KeyCode
import maestro.Point
import maestro.TapRepeat
import maestro.orchestra.*
import maestro.orchestra.error.InvalidFlowFile
import maestro.orchestra.error.MediaFileNotFound
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.util.Env.withEnv
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

data class YamlFluentCommand(
    val tapOn: YamlElementSelectorUnion? = null,
    val doubleTapOn: YamlElementSelectorUnion? = null,
    val longPressOn: YamlElementSelectorUnion? = null,
    val assertVisible: YamlElementSelectorUnion? = null,
    val assertNotVisible: YamlElementSelectorUnion? = null,
    val assertTrue: YamlAssertTrue? = null,
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
) {

    @SuppressWarnings("ComplexMethod")
    fun toCommands(flowPath: Path, appId: String): List<MaestroCommand> {
        return when {
            launchApp != null -> listOf(launchApp(launchApp, appId))
            tapOn != null -> listOf(tapCommand(tapOn))
            longPressOn != null -> listOf(tapCommand(longPressOn, longPress = true))
            assertVisible != null -> listOf(
                MaestroCommand(
                    AssertConditionCommand(
                        condition = Condition(
                            visible = toElementSelector(assertVisible),
                        ),
                        label = (assertVisible as? YamlElementSelector)?.label
                    )
                )
            )
            assertNotVisible != null -> listOf(
                MaestroCommand(
                    AssertConditionCommand(
                        condition = Condition(
                            notVisible = toElementSelector(assertNotVisible),
                        ),
                        label = (assertNotVisible as? YamlElementSelector)?.label
                    )
                )
            )
            assertTrue != null -> listOf(
                MaestroCommand(
                    AssertConditionCommand(
                        Condition(
                            scriptCondition = assertTrue.condition,
                        ),
                        label = assertTrue.label
                    )
                )
            )
            addMedia != null -> listOf(
                MaestroCommand(
                    addMediaCommand = addMediaCommand(addMedia, flowPath)
                )
            )
            inputText != null -> listOf(MaestroCommand(InputTextCommand(text = inputText.text, label = inputText.label)))
            inputRandomText != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.TEXT, length = inputRandomText.length, label = inputRandomText.label)))
            inputRandomNumber != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.NUMBER, length = inputRandomNumber.length, label = inputRandomNumber.label)))
            inputRandomEmail != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.TEXT_EMAIL_ADDRESS, label = inputRandomEmail.label)))
            inputRandomPersonName != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.TEXT_PERSON_NAME, label = inputRandomPersonName.label)))
            swipe != null -> listOf(swipeCommand(swipe))
            openLink != null -> listOf(MaestroCommand(OpenLinkCommand(link = openLink.link, autoVerify = openLink.autoVerify, browser =  openLink.browser, label = openLink.label)))
            pressKey != null -> listOf(MaestroCommand(PressKeyCommand(code = KeyCode.getByName(pressKey.key) ?: throw SyntaxError("Unknown key name: $pressKey"), label = pressKey.label)))
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
            back != null -> listOf(MaestroCommand(BackPressCommand(label = back.label)))
            clearKeychain != null -> listOf(MaestroCommand(ClearKeychainCommand(label = clearKeychain.label)))
            hideKeyboard != null -> listOf(MaestroCommand(HideKeyboardCommand(label = hideKeyboard.label)))
            pasteText != null -> listOf(MaestroCommand(PasteTextCommand(label = pasteText.label)))
            scroll != null -> listOf(MaestroCommand(ScrollCommand(label = scroll.label)))
            takeScreenshot != null -> listOf(MaestroCommand(TakeScreenshotCommand(path = takeScreenshot.path, label = takeScreenshot.label)))
            extendedWaitUntil != null -> listOf(extendedWait(extendedWaitUntil))
            stopApp != null -> listOf(
                MaestroCommand(
                    StopAppCommand(
                        appId = stopApp.appId ?: appId,
                        label = stopApp.label
                    )
                )
            )
            killApp != null -> listOf(
                MaestroCommand(
                    KillAppCommand(
                        appId = killApp.appId ?: appId,
                        label = killApp.label
                    )
                )
            )
            clearState != null -> listOf(
                MaestroCommand(
                    maestro.orchestra.ClearStateCommand(
                        appId = clearState.appId ?: appId,
                        label = clearState.label
                    )
                )
            )
            runFlow != null -> listOf(runFlowCommand(appId, flowPath, runFlow))
            setLocation != null -> listOf(
                MaestroCommand(
                    SetLocationCommand(
                        latitude = setLocation.latitude,
                        longitude = setLocation.longitude,
                        label = setLocation.label
                    )
                )
            )
            repeat != null -> listOf(
                repeatCommand(repeat, flowPath, appId)
            )
            copyTextFrom != null -> listOf(copyTextFromCommand(copyTextFrom))
            runScript != null -> listOf(
                MaestroCommand(
                    RunScriptCommand(
                        script = resolvePath(flowPath, runScript.file)
                            .readText(),
                        env = runScript.env,
                        sourceDescription = runScript.file,
                        condition = runScript.`when`?.toCondition(),
                        label = runScript.label
                    )
                )
            )
            waitForAnimationToEnd != null -> listOf(
                MaestroCommand(
                    WaitForAnimationToEndCommand(
                        timeout = waitForAnimationToEnd.timeout,
                        label = waitForAnimationToEnd.label
                    )
                )
            )
            evalScript != null -> listOf(
                MaestroCommand(
                    EvalScriptCommand(
                        scriptString = evalScript.script,
                        label = evalScript.label
                    )
                )
            )
            scrollUntilVisible != null -> listOf(scrollUntilVisibleCommand(scrollUntilVisible))
            travel != null -> listOf(travelCommand(travel))
            startRecording != null -> listOf(MaestroCommand(StartRecordingCommand(startRecording.path, startRecording.label)))
            stopRecording != null -> listOf(MaestroCommand(StopRecordingCommand(stopRecording.label)))
            doubleTapOn != null -> {
                val yamlDelay = (doubleTapOn as? YamlElementSelector)?.delay?.toLong()
                val delay = if (yamlDelay != null && yamlDelay >= 0) yamlDelay else TapOnElementCommand.DEFAULT_REPEAT_DELAY
                val tapRepeat = TapRepeat(2, delay)
                listOf(tapCommand(doubleTapOn, tapRepeat = tapRepeat))
            }
            setAirplaneMode != null -> listOf(MaestroCommand(SetAirplaneModeCommand(setAirplaneMode.value)))
            toggleAirplaneMode != null -> listOf(MaestroCommand(ToggleAirplaneModeCommand))
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
        return AddMediaCommand(mediaAbsolutePathStrings)
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
                label = runFlow.label
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

                        val latitude = spitPoint[0].toDoubleOrNull() ?: throw SyntaxError("Invalid travel point latitude: $point")
                        val longitude = spitPoint[1].toDoubleOrNull() ?: throw SyntaxError("Invalid travel point longitude: $point")

                        TravelCommand.GeoPoint(
                            latitude = latitude,
                            longitude = longitude,
                        )
                    },
                speedMPS = command.speed,
                label = command.label,
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
        )
    )

    private fun eraseCommand(eraseText: YamlEraseText): MaestroCommand {
        return if (eraseText.charactersToErase != null) {
            MaestroCommand(EraseTextCommand(charactersToErase = eraseText.charactersToErase, label = eraseText.label))
        } else {
            MaestroCommand(EraseTextCommand(charactersToErase = null, label = eraseText.label))
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
            throw InvalidFlowFile("Referenced Flow file can't be the same as the main Flow file: ${resolvedPath.toUri()}", resolvedPath)
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
            )
        )
    }

    private fun tapCommand(
        tapOn: YamlElementSelectorUnion,
        longPress: Boolean = false,
        tapRepeat: TapRepeat? = null
    ): MaestroCommand {
        val retryIfNoChange = (tapOn as? YamlElementSelector)?.retryTapIfNoChange ?: true
        val waitUntilVisible = (tapOn as? YamlElementSelector)?.waitUntilVisible ?: false
        val point = (tapOn as? YamlElementSelector)?.point
        val label = (tapOn as? YamlElementSelector)?.label

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
            MaestroCommand(
                TapOnPointV2Command(
                    point = point,
                    retryIfNoChange = retryIfNoChange,
                    longPress = longPress,
                    repeat = repeat,
                    waitToSettleTimeoutMs = waitToSettleTimeoutMs,
                    label = label
                )
            )
        } else {
            MaestroCommand(
                command = TapOnElementCommand(
                    selector = toElementSelector(tapOn),
                    retryIfNoChange = retryIfNoChange,
                    waitUntilVisible = waitUntilVisible,
                    longPress = longPress,
                    repeat = repeat,
                    waitToSettleTimeoutMs = waitToSettleTimeoutMs,
                    label = label
                )
            )
        }
    }

    private fun swipeCommand(swipe: YamlSwipe): MaestroCommand {
        when (swipe) {
            is YamlSwipeDirection -> return MaestroCommand(SwipeCommand(direction = swipe.direction, duration = swipe.duration, label = swipe.label))
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

                return MaestroCommand(SwipeCommand(startPoint = startPoint, endPoint = endPoint, duration = swipe.duration, label = swipe.label))
            }
            is YamlRelativeCoordinateSwipe -> {
                return MaestroCommand(
                    SwipeCommand(startRelative = swipe.start, endRelative = swipe.end, duration = swipe.duration, label = swipe.label)
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
            optional = selector.optional ?: false,
            childOf = selector.childOf?.let { toElementSelector(it) }
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
                    label = (copyText as? YamlElementSelector)?.label
                )
            )
        }
    }

    private fun scrollUntilVisibleCommand(yaml: YamlScrollUntilVisible): MaestroCommand {
        val timeout =
            if (yaml.timeout < 0) {
                ScrollUntilVisibleCommand.DEFAULT_TIMEOUT_IN_MILLIS
            } else yaml.timeout

        val visibility = if (yaml.visibilityPercentage < 0) 0 else if (yaml.visibilityPercentage > 100) 100 else yaml.visibilityPercentage
        return MaestroCommand(
            ScrollUntilVisibleCommand(
                selector = toElementSelector(yaml.element),
                direction = yaml.direction,
                timeout = timeout,
                scrollDuration = yaml.speedToDuration(),
                visibilityPercentage = visibility,
                centerElement = yaml.centerElement,
                label = yaml.label
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

    companion object {

        @Suppress("unused")
        @JvmStatic
        @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
        fun parse(stringCommand: String): YamlFluentCommand {
            return when (stringCommand) {
                "launchApp" -> YamlFluentCommand(
                    launchApp = YamlLaunchApp(
                        appId = null,
                        clearState = null,
                        clearKeychain = null,
                        stopApp = null,
                        permissions = null,
                        arguments = null,
                    )
                )

                "stopApp" -> YamlFluentCommand(
                    stopApp = YamlStopApp()
                )

                "killApp" -> YamlFluentCommand(
                    killApp = YamlKillApp()
                )

                "clearState" -> YamlFluentCommand(
                    clearState = YamlClearState(
                        appId = null,
                    )
                )

                "clearKeychain" -> YamlFluentCommand(
                    clearKeychain = YamlActionClearKeychain(),
                )

                "eraseText" -> YamlFluentCommand(
                    eraseText = YamlEraseText(charactersToErase = null)
                )

                "inputRandomText" -> YamlFluentCommand(
                    inputRandomText = YamlInputRandomText(length = 8),
                )

                "inputRandomNumber" -> YamlFluentCommand(
                    inputRandomNumber = YamlInputRandomNumber(length = 8),
                )

                "inputRandomEmail" -> YamlFluentCommand(
                    inputRandomEmail = YamlInputRandomEmail(),
                )

                "inputRandomPersonName" -> YamlFluentCommand(
                    inputRandomPersonName = YamlInputRandomPersonName(),
                )

                "back" -> YamlFluentCommand(
                    back = YamlActionBack(),
                )

                "hide keyboard", "hideKeyboard" -> YamlFluentCommand(
                    hideKeyboard = YamlActionHideKeyboard(),
                )

                "pasteText" -> YamlFluentCommand(
                    pasteText = YamlActionPasteText(),
                )

                "scroll" -> YamlFluentCommand(
                    scroll = YamlActionScroll(),
                )

                "waitForAnimationToEnd" -> YamlFluentCommand(
                    waitForAnimationToEnd = YamlWaitForAnimationToEndCommand(timeout = null)
                )

                "stopRecording" -> YamlFluentCommand(
                    stopRecording = YamlStopRecording()
                )

                "toggleAirplaneMode" -> YamlFluentCommand(
                    toggleAirplaneMode = YamlToggleAirplaneMode()
                )

                else -> throw SyntaxError("Invalid command: \"$stringCommand\"")
            }
        }
    }
}
