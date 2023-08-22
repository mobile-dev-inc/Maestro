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
import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonProcessingException
import maestro.KeyCode
import maestro.Point
import maestro.TapRepeat
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.BackPressCommand
import maestro.orchestra.ClearKeychainCommand
import maestro.orchestra.Condition
import maestro.orchestra.CopyTextFromCommand
import maestro.orchestra.ElementSelector
import maestro.orchestra.ElementTrait
import maestro.orchestra.EraseTextCommand
import maestro.orchestra.EvalScriptCommand
import maestro.orchestra.HideKeyboardCommand
import maestro.orchestra.InputRandomCommand
import maestro.orchestra.InputRandomType
import maestro.orchestra.InputTextCommand
import maestro.orchestra.LaunchAppCommand
import maestro.orchestra.MaestroCommand
import maestro.orchestra.MaestroConfig
import maestro.orchestra.OpenLinkCommand
import maestro.orchestra.PasteTextCommand
import maestro.orchestra.PressKeyCommand
import maestro.orchestra.RepeatCommand
import maestro.orchestra.RunFlowCommand
import maestro.orchestra.RunScriptCommand
import maestro.orchestra.ScrollCommand
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.SetLocationCommand
import maestro.orchestra.StartRecordingCommand
import maestro.orchestra.StopAppCommand
import maestro.orchestra.StopRecordingCommand
import maestro.orchestra.SwipeCommand
import maestro.orchestra.TakeScreenshotCommand
import maestro.orchestra.TapOnElementCommand
import maestro.orchestra.TapOnPointV2Command
import maestro.orchestra.TravelCommand
import maestro.orchestra.WaitForAnimationToEndCommand
import maestro.orchestra.error.InvalidFlowFile
import maestro.orchestra.error.SyntaxError
import maestro.orchestra.util.Env.withEnv
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.readText

data class YamlFluentCommand(
    val tapOn: YamlElementSelectorUnion? = null,
    val doubleTapOn: YamlElementSelectorUnion? = null,
    val longPressOn: YamlElementSelectorUnion? = null,
    val assertVisible: YamlElementSelectorUnion? = null,
    val assertNotVisible: YamlElementSelectorUnion? = null,
    val assertTrue: String? = null,
    val action: String? = null,
    val inputText: String? = null,
    val inputRandomText: YamlInputRandomText? = null,
    val inputRandomNumber: YamlInputRandomNumber? = null,
    val inputRandomEmail: YamlInputRandomEmail? = null,
    val inputRandomPersonName: YamlInputRandomPersonName? = null,
    val launchApp: YamlLaunchApp? = null,
    val swipe: YamlSwipe? = null,
    val openLink: YamlOpenLink? = null,
    val openBrowser: String? = null,
    val pressKey: String? = null,
    val eraseText: YamlEraseText? = null,
    val takeScreenshot: YamlTakeScreenshot? = null,
    val extendedWaitUntil: YamlExtendedWaitUntil? = null,
    val stopApp: YamlStopApp? = null,
    val clearState: YamlClearState? = null,
    val runFlow: YamlRunFlow? = null,
    val setLocation: YamlSetLocation? = null,
    val repeat: YamlRepeatCommand? = null,
    val copyTextFrom: YamlElementSelectorUnion? = null,
    val runScript: YamlRunScript? = null,
    val waitForAnimationToEnd: YamlWaitForAnimationToEndCommand? = null,
    val evalScript: String? = null,
    val scrollUntilVisible: YamlScrollUntilVisible? = null,
    val travel: YamlTravelCommand? = null,
    val startRecording: YamlStartRecording? = null,
    val stopRecording: YamlStopRecording? = null,
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
                        Condition(
                            visible = toElementSelector(assertVisible),
                        )
                    )
                )
            )
            assertNotVisible != null -> listOf(
                MaestroCommand(
                    AssertConditionCommand(
                        Condition(
                            notVisible = toElementSelector(assertNotVisible),
                        )
                    )
                )
            )
            assertTrue != null -> listOf(
                MaestroCommand(
                    AssertConditionCommand(
                        Condition(
                            scriptCondition = assertTrue,
                        )
                    )
                )
            )
            inputText != null -> listOf(MaestroCommand(InputTextCommand(inputText)))
            inputRandomText != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.TEXT, length = inputRandomText.length)))
            inputRandomNumber != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.NUMBER, length = inputRandomNumber.length)))
            inputRandomEmail != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.TEXT_EMAIL_ADDRESS)))
            inputRandomPersonName != null -> listOf(MaestroCommand(InputRandomCommand(inputType = InputRandomType.TEXT_PERSON_NAME)))
            swipe != null -> listOf(swipeCommand(swipe))
            openLink != null -> listOf(MaestroCommand(OpenLinkCommand(openLink.link, openLink.autoVerify, openLink.browser)))
            pressKey != null -> listOf(MaestroCommand(PressKeyCommand(code = KeyCode.getByName(pressKey) ?: throw SyntaxError("Unknown key name: $pressKey"))))
            eraseText != null -> listOf(eraseCommand(eraseText))
            action != null -> listOf(
                when (action) {
                    "back" -> MaestroCommand(BackPressCommand())
                    "hide keyboard" -> MaestroCommand(HideKeyboardCommand())
                    "scroll" -> MaestroCommand(ScrollCommand())
                    "clearKeychain" -> MaestroCommand(ClearKeychainCommand())
                    "pasteText" -> MaestroCommand(PasteTextCommand())
                    else -> error("Unknown navigation target: $action")
                }
            )
            takeScreenshot != null -> listOf(MaestroCommand(TakeScreenshotCommand(takeScreenshot.path)))
            extendedWaitUntil != null -> listOf(extendedWait(extendedWaitUntil))
            stopApp != null -> listOf(
                MaestroCommand(
                    StopAppCommand(
                        appId = stopApp.appId ?: appId,
                    )
                )
            )
            clearState != null -> listOf(
                MaestroCommand(
                    maestro.orchestra.ClearStateCommand(
                        appId = clearState.appId ?: appId,
                    )
                )
            )
            runFlow != null -> listOf(runFlowCommand(appId, flowPath, runFlow))
            setLocation != null -> listOf(
                MaestroCommand(
                    SetLocationCommand(
                        latitude = setLocation.latitude,
                        longitude = setLocation.longitude,
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
                    )
                )
            )
            waitForAnimationToEnd != null -> listOf(
                MaestroCommand(
                    WaitForAnimationToEndCommand(
                        timeout = waitForAnimationToEnd.timeout
                    )
                )
            )
            evalScript != null -> listOf(
                MaestroCommand(
                    EvalScriptCommand(
                        scriptString = evalScript,
                    )
                )
            )
            scrollUntilVisible != null -> listOf(scrollUntilVisibleCommand(scrollUntilVisible))
            travel != null -> listOf(travelCommand(travel))
            startRecording != null -> listOf(MaestroCommand(StartRecordingCommand(startRecording.path)))
            stopRecording != null -> listOf(MaestroCommand(StopRecordingCommand()))
            doubleTapOn != null -> {
                val yamlDelay = (doubleTapOn as? YamlElementSelector)?.delay?.toLong()
                val delay = if (yamlDelay != null && yamlDelay >= 0) yamlDelay else TapOnElementCommand.DEFAULT_REPEAT_DELAY
                val tapRepeat = TapRepeat(2, delay)
                listOf(tapCommand(doubleTapOn, tapRepeat = tapRepeat))
            }
            else -> throw SyntaxError("Invalid command: No mapping provided for $this")
        }
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
                config
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
            )
        )
    }

    private fun repeatCommand(repeat: YamlRepeatCommand, flowPath: Path, appId: String) = MaestroCommand(
        RepeatCommand(
            times = repeat.times,
            condition = repeat.`while`?.toCondition(),
            commands = repeat.commands
                .flatMap { it.toCommands(flowPath, appId) },
        )
    )

    private fun eraseCommand(eraseText: YamlEraseText): MaestroCommand {
        return if (eraseText.charactersToErase != null) {
            MaestroCommand(EraseTextCommand(charactersToErase = eraseText.charactersToErase))
        } else {
            MaestroCommand(EraseTextCommand(charactersToErase = null))
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

        val delay = (tapOn as? YamlElementSelector)?.delay?.toLong()
        val repeat = tapRepeat ?: (tapOn as? YamlElementSelector)?.repeat?.let {
            val count = if (it <= 0) 1 else it
            val d = if (delay != null && delay >= 0) delay else TapOnElementCommand.DEFAULT_REPEAT_DELAY
            TapRepeat(count, d)
        }

        return if (point != null) {
            MaestroCommand(
                TapOnPointV2Command(
                    point = point,
                    retryIfNoChange = retryIfNoChange,
                    longPress = longPress,
                    repeat = repeat
                )
            )
        } else {
            MaestroCommand(
                command = TapOnElementCommand(
                    selector = toElementSelector(tapOn),
                    retryIfNoChange = retryIfNoChange,
                    waitUntilVisible = waitUntilVisible,
                    longPress = longPress,
                    repeat = repeat
                )
            )
        }
    }

    private fun swipeCommand(swipe: YamlSwipe): MaestroCommand {
        when (swipe) {
            is YamlSwipeDirection -> return MaestroCommand(SwipeCommand(direction = swipe.direction, duration = swipe.duration))
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

                return MaestroCommand(SwipeCommand(startPoint = startPoint, endPoint = endPoint, duration = swipe.duration))
            }
            is YamlRelativeCoordinateSwipe -> {
                return MaestroCommand(
                    SwipeCommand(startRelative = swipe.start, endRelative = swipe.end, duration = swipe.duration)
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
                duration = swipeElement.duration
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
        )
    }

    private fun copyTextFromCommand(
        copyText: YamlElementSelectorUnion
    ): MaestroCommand {
        return MaestroCommand(
            CopyTextFromCommand(
                selector = toElementSelector(copyText)
            )
        )
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
                visibilityPercentage = visibility
            )
        )
    }

    private fun YamlCondition.toCondition(): Condition {
        return Condition(
            platform = platform,
            visible = visible?.let { toElementSelector(it) },
            notVisible = notVisible?.let { toElementSelector(it) },
            scriptCondition = `true`?.trim(),
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

                "clearState" -> YamlFluentCommand(
                    clearState = YamlClearState(
                        appId = null,
                    )
                )

                "clearKeychain" -> YamlFluentCommand(
                    action = "clearKeychain"
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
                    action = "back"
                )

                "hide keyboard", "hideKeyboard" -> YamlFluentCommand(
                    action = "hide keyboard"
                )

                "pasteText" -> YamlFluentCommand(
                    action = "pasteText"
                )

                "scroll" -> YamlFluentCommand(
                    action = "scroll"
                )

                "waitForAnimationToEnd" -> YamlFluentCommand(
                    waitForAnimationToEnd = YamlWaitForAnimationToEndCommand(null)
                )

                "stopRecording" -> YamlFluentCommand(
                    stopRecording = YamlStopRecording()
                )

                else -> throw SyntaxError("Invalid command: \"$stringCommand\"")
            }
        }
    }
}
