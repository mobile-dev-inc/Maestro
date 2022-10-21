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

import maestro.DeviceInfo
import maestro.ElementFilter
import maestro.Filters
import maestro.Filters.asFilter
import maestro.KeyCode
import maestro.Maestro
import maestro.MaestroException
import maestro.MaestroTimer
import maestro.TreeNode
import maestro.UiElement
import maestro.orchestra.error.UnicodeNotSupportedError
import maestro.orchestra.filter.FilterWithDescription
import maestro.orchestra.filter.TraitFilters
import maestro.orchestra.yaml.YamlCommandReader
import java.io.File
import java.nio.file.Files

class Orchestra(
    private val maestro: Maestro,
    private val stateDir: File? = null,
    private val screenshotsDir: File? = null,
    private val lookupTimeoutMs: Long = 15000L,
    private val optionalLookupTimeoutMs: Long = 5000L,
    private val onFlowStart: (List<MaestroCommand>) -> Unit = {},
    private val onCommandStart: (Int, MaestroCommand) -> Unit = { _, _ -> },
    private val onCommandComplete: (Int, MaestroCommand) -> Unit = { _, _ -> },
    private val onCommandFailed: (Int, MaestroCommand, Throwable) -> ErrorResolution = { _, _, e -> throw e },
    private val onCommandSkipped: (Int, MaestroCommand) -> Unit = { _, _ -> },
    private val onCommandReset: (MaestroCommand) -> Unit = {},
    private val onCommandMetadataUpdate: (MaestroCommand, CommandMetadata) -> Unit = { _, _ -> },
) {

    /**
     * If initState is provided, initialize app disk state with the provided OrchestraAppState and skip
     * any initFlow execution. Otherwise, initialize app state with initFlow if defined.
     */
    fun runFlow(
        commands: List<MaestroCommand>,
        initState: OrchestraAppState? = null,
    ): Boolean {
        val config = YamlCommandReader.getConfig(commands)
        val state = initState ?: config?.initFlow?.let {
            runInitFlow(it) ?: return false
        }

        if (state != null) {
            maestro.clearAppState(state.appId)
            maestro.pushAppState(state.appId, state.file)
        }

        onFlowStart(commands)
        return executeCommands(commands)
    }

    /**
     * Run the initFlow and return the resulting app OrchestraAppState which can be used to initialize
     * app disk state when past into Orchestra.runFlow.
     */
    fun runInitFlow(
        initFlow: MaestroInitFlow,
    ): OrchestraAppState? {
        val success = runFlow(
            initFlow.commands,
            initState = null,
        )
        if (!success) return null

        maestro.stopApp(initFlow.appId)

        val stateFile = if (stateDir == null) {
            Files.createTempFile(null, ".state")
        } else {
            Files.createTempFile(stateDir.toPath(), null, ".state")
        }
        maestro.pullAppState(initFlow.appId, stateFile.toFile())

        return OrchestraAppState(
            appId = initFlow.appId,
            file = stateFile.toFile(),
        )
    }

    private fun executeCommands(
        commands: List<MaestroCommand>,
    ): Boolean {
        commands
            .forEachIndexed { index, command ->
                onCommandStart(index, command)
                try {
                    executeCommand(command)
                    onCommandComplete(index, command)
                } catch (ignored: CommandSkipped) {
                    // Swallow exception
                    onCommandSkipped(index, command)
                } catch (e: Throwable) {
                    when (onCommandFailed(index, command, e)) {
                        ErrorResolution.FAIL -> return false
                        ErrorResolution.CONTINUE -> {
                            // Do nothing
                        }
                    }
                }
            }
        return true
    }

    private fun executeCommand(maestroCommand: MaestroCommand) {
        val command = maestroCommand.asCommand()

        return when (command) {
            is TapOnElementCommand -> {
                tapOnElement(command, command.retryIfNoChange ?: true, command.waitUntilVisible ?: true)
            }
            is TapOnPointCommand -> tapOnPoint(command, command.retryIfNoChange ?: true)
            is BackPressCommand -> maestro.backPress()
            is HideKeyboardCommand -> maestro.hideKeyboard()
            is ClipboardPasteCommand -> maestro.clipboardPaste()
            is ScrollCommand -> maestro.scrollVertical()
            is SwipeCommand -> swipeCommand(command)
            is AssertCommand -> assertCommand(command)
            is InputTextCommand -> inputTextCommand(command)
            is InputRandomCommand -> inputTextRandomCommand(command)
            is LaunchAppCommand -> launchAppCommand(command)
            is OpenLinkCommand -> openLinkCommand(command)
            is PressKeyCommand -> pressKeyCommand(command)
            is EraseTextCommand -> eraseTextCommand(command)
            is TakeScreenshotCommand -> takeScreenshotCommand(command)
            is StopAppCommand -> maestro.stopApp(command.appId)
            is ClearStateCommand -> maestro.clearAppState(command.appId)
            is ClearKeychainCommand -> maestro.clearKeychain()
            is RunFlowCommand -> runFlowCommand(command)
            is SetLocationCommand -> maestro.setLocation(command.latitude, command.longitude)
            is RepeatCommand -> repeatCommand(command, maestroCommand)
            is ApplyConfigurationCommand, null -> { /* no-op */
            }
        }
    }

    private fun repeatCommand(command: RepeatCommand, maestroCommand: MaestroCommand) {
        val maxRuns = command.times?.toIntOrNull() ?: Int.MAX_VALUE

        var counter = 0
        var metadata = CommandMetadata(
            numberOfRuns = 0,
        )

        while ((command.condition?.let { evaluateCondition(it) } != false) && counter < maxRuns) {
            if (counter > 0) {
                command.commands.forEach { resetCommand(it) }
            }

            runSubFlow(command.commands)
            counter++

            metadata = metadata.copy(
                numberOfRuns = counter,
            )
            onCommandMetadataUpdate(maestroCommand, metadata)
        }

        if (counter == 0) {
            throw CommandSkipped
        }
    }

    private fun resetCommand(command: MaestroCommand) {
        onCommandReset(command)

        (command.asCommand() as? CompositeCommand)?.let {
            it.subCommands().forEach { command ->
                resetCommand(command)
            }
        }
    }

    private fun runFlowCommand(command: RunFlowCommand) {
        if (evaluateCondition(command.condition)) {
            runSubFlow(command.commands)
        } else {
            throw CommandSkipped
        }
    }

    private fun evaluateCondition(condition: Condition?): Boolean {
        if (condition == null) {
            return true
        }

        condition.visible?.let {
            try {
                findElement(it, timeoutMs = optionalLookupTimeoutMs)
            } catch (ignored: MaestroException.ElementNotFound) {
                return false
            }
        }

        condition.notVisible?.let {
            val result = MaestroTimer.withTimeout(optionalLookupTimeoutMs) {
                try {
                    findElement(it, timeoutMs = 500L)

                    // If we got to that point, the element is still visible.
                    // Returning null to keep waiting.
                    null
                } catch (ignored: MaestroException.ElementNotFound) {
                    // Element was not visible, as we expected
                    true
                }
            }

            // Element was actually visible
            if (result != true) {
                return false
            }
        }

        return true
    }

    private fun runSubFlow(commands: List<MaestroCommand>) {
        commands
            .forEachIndexed { index, command ->
                onCommandStart(index, command)
                try {
                    executeCommand(command)
                    onCommandComplete(index, command)
                } catch (ignored: CommandSkipped) {
                    // Swallow exception
                    onCommandSkipped(index, command)
                } catch (e: Throwable) {
                    when (onCommandFailed(index, command, e)) {
                        ErrorResolution.FAIL -> throw e
                        ErrorResolution.CONTINUE -> {
                            // Do nothing
                        }
                    }
                }
            }
    }

    private fun takeScreenshotCommand(command: TakeScreenshotCommand) {
        val pathStr = command.path + ".png"
        val file = screenshotsDir
            ?.let { File(it, pathStr) }
            ?: File(pathStr)

        maestro.takeScreenshot(file)
    }

    private fun eraseTextCommand(command: EraseTextCommand) {
        repeat(command.charactersToErase) {
            maestro.pressKey(KeyCode.BACKSPACE)
        }
    }

    private fun pressKeyCommand(command: PressKeyCommand) {
        maestro.pressKey(command.code)
    }

    private fun openLinkCommand(command: OpenLinkCommand) {
        maestro.openLink(command.link)
    }

    private fun launchAppCommand(it: LaunchAppCommand) {
        try {
            if (it.clearKeychain == true) {
                maestro.clearKeychain()
            }
            if (it.clearState == true) {
                maestro.clearAppState(it.appId)
            }
        } catch (e: Exception) {
            throw MaestroException.UnableToClearState("Unable to clear state for app ${it.appId}")
        }

        try {
            maestro.launchApp(it.appId)
        } catch (e: Exception) {
            throw MaestroException.UnableToLaunchApp("Unable to launch app ${it.appId}: ${e.message}")
        }
    }

    private fun inputTextCommand(command: InputTextCommand) {
        val isAscii = Charsets.US_ASCII.newEncoder()
            .canEncode(command.text)

        if (!isAscii) {
            throw UnicodeNotSupportedError(command.text)
        }

        maestro.inputText(command.text)
    }

    private fun inputTextRandomCommand(command: InputRandomCommand) {
        inputTextCommand(InputTextCommand(text = command.genRandomString()))
    }

    private fun assertCommand(command: AssertCommand) {
        command.visible?.let { assertVisible(it, command.timeout) }
        command.notVisible?.let { assertNotVisible(it, command.timeout) }
    }

    private fun assertNotVisible(selector: ElementSelector, timeoutMs: Long?) {
        val result = MaestroTimer.withTimeout(timeoutMs ?: lookupTimeoutMs) {
            try {
                findElement(selector, timeoutMs = 2000L)

                // If we got to that point, the element is still visible.
                // Returning null to keep waiting.
                null
            } catch (ignored: MaestroException.ElementNotFound) {
                // Element was not visible, as we expected
                true
            }
        }

        if (result != true) {
            // If we got to this point, it means that element is actually visible
            throw MaestroException.AssertionFailure(
                "${selector.description()} is visible",
                maestro.viewHierarchy().root,
            )
        }
    }

    private fun assertVisible(selector: ElementSelector, timeout: Long?) {
        findElement(selector, timeout) // Throws if element is not found
    }

    private fun tapOnElement(
        command: TapOnElementCommand,
        retryIfNoChange: Boolean,
        waitUntilVisible: Boolean,
    ) {
        try {
            val element = findElement(command.selector)
            maestro.tap(
                element,
                retryIfNoChange,
                waitUntilVisible,
                command.longPress ?: false,
            )
        } catch (e: MaestroException.ElementNotFound) {

            if (!command.selector.optional) {
                throw e
            }
        }
    }

    private fun tapOnPoint(
        command: TapOnPointCommand,
        retryIfNoChange: Boolean,
    ) {
        maestro.tap(
            command.x,
            command.y,
            retryIfNoChange,
            command.longPress ?: false,
        )
    }

    private fun findElement(
        selector: ElementSelector,
        timeoutMs: Long? = null
    ): UiElement {
        val timeout = timeoutMs
            ?: if (selector.optional) {
                optionalLookupTimeoutMs
            } else {
                lookupTimeoutMs
            }

        val (description, filterFunc) = buildFilter(
            selector,
            maestro.deviceInfo(),
            maestro.viewHierarchy().aggregate(),
        )

        return maestro.findElementWithTimeout(
            timeoutMs = timeout,
            filter = filterFunc,
        ) ?: throw MaestroException.ElementNotFound(
            "Element not found: $description",
            maestro.viewHierarchy().root,
        )
    }

    private fun buildFilter(
        selector: ElementSelector,
        deviceInfo: DeviceInfo,
        allNodes: List<TreeNode>,
    ): FilterWithDescription {
        val filters = mutableListOf<ElementFilter>()
        val descriptions = mutableListOf<String>()

        selector.textRegex
            ?.let {
                descriptions += "Text matching regex: $it"
                filters += Filters.textMatches(it.toRegex(REGEX_OPTIONS)).asFilter()
            }

        selector.idRegex
            ?.let {
                descriptions += "Id matching regex: $it"
                filters += Filters.idMatches(it.toRegex(REGEX_OPTIONS))
            }

        selector.size
            ?.let {
                descriptions += "Size: $it"
                filters += Filters.sizeMatches(
                    width = it.width,
                    height = it.height,
                    tolerance = it.tolerance,
                ).asFilter()
            }

        selector.below
            ?.let {
                descriptions += "Below: ${it.description()}"
                filters += Filters.below(buildFilter(it, deviceInfo, allNodes).filterFunc)
            }

        selector.above
            ?.let {
                descriptions += "Above: ${it.description()}"
                filters += Filters.above(buildFilter(it, deviceInfo, allNodes).filterFunc)
            }

        selector.leftOf
            ?.let {
                descriptions += "Left of: ${it.description()}"
                filters += Filters.leftOf(buildFilter(it, deviceInfo, allNodes).filterFunc)
            }

        selector.rightOf
            ?.let {
                descriptions += "Right of: ${it.description()}"
                filters += Filters.rightOf(buildFilter(it, deviceInfo, allNodes).filterFunc)
            }

        selector.containsChild
            ?.let {
                descriptions += "Contains child: ${it.description()}"
                filters += Filters.containsChild(findElement(it)).asFilter()
            }

        selector.traits
            ?.map {
                TraitFilters.buildFilter(it)
            }
            ?.forEach { (description, filter) ->
                descriptions += description
                filters += filter
            }

        selector.enabled
            ?.let {
                descriptions += if (it) {
                    "Enabled"
                } else {
                    "Disabled"
                }
                filters += Filters.enabled(it)
            }

        var resultFilter = Filters.intersect(filters)
        resultFilter = selector.index
            ?.let {
                Filters.compose(
                    resultFilter,
                    Filters.index(it)
                )
            } ?: Filters.compose(
            resultFilter,
            Filters.clickableFirst()
        )

        return FilterWithDescription(
            descriptions.joinToString(", "),
            resultFilter,
        )
    }

    private fun swipeCommand(command: SwipeCommand) {
        maestro.swipe(command.startPoint, command.endPoint)
    }

    private object CommandSkipped : Exception()

    data class CommandMetadata(
        val numberOfRuns: Int? = null,
    )

    enum class ErrorResolution {
        CONTINUE,
        FAIL,
    }

    companion object {

        val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)

    }
}

data class OrchestraAppState(
    val appId: String,
    val file: File,
)
