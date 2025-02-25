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

import kotlinx.coroutines.runBlocking
import maestro.Driver
import maestro.ElementFilter
import maestro.Filters
import maestro.Filters.asFilter
import maestro.FindElementResult
import maestro.Maestro
import maestro.MaestroException
import maestro.ScreenRecording
import maestro.ViewHierarchy
import maestro.ai.AI
import maestro.ai.AI.Companion.AI_KEY_ENV_VAR
import maestro.ai.Prediction
import maestro.ai.anthropic.Claude
import maestro.ai.cloud.Defect
import maestro.ai.openai.OpenAI
import maestro.js.GraalJsEngine
import maestro.js.JsEngine
import maestro.js.RhinoJsEngine
import maestro.orchestra.error.UnicodeNotSupportedError
import maestro.orchestra.filter.FilterWithDescription
import maestro.orchestra.filter.TraitFilters
import maestro.orchestra.geo.Traveller
import maestro.orchestra.util.Env.evaluateScripts
import maestro.orchestra.yaml.YamlCommandReader
import maestro.toSwipeDirection
import maestro.utils.Insight
import maestro.utils.Insights
import maestro.utils.MaestroTimer
import maestro.utils.NoopInsights
import maestro.utils.StringUtils.toRegexSafe
import okhttp3.OkHttpClient
import okio.Buffer
import okio.Sink
import okio.buffer
import okio.sink
import org.slf4j.LoggerFactory
import java.io.File
import java.lang.Long.max

// TODO(bartkepacia): Use this in onCommandGeneratedOutput.
//  Caveat:
//    Large files should not be held in memory, instead they should be directly written to a Buffer
//    that is streamed to disk.
//  Idea:
//    Orchestra should expose a callback like "onResourceRequested: (Command, CommandOutputType)"
sealed class CommandOutput {
    data class Screenshot(val screenshot: Buffer) : CommandOutput()
    data class ScreenRecording(val screenRecording: Buffer) : CommandOutput()
    data class AIDefects(val defects: List<Defect>, val screenshot: Buffer) : CommandOutput()
}

/**
 * Orchestra translates high-level Maestro commands into method calls on the [Maestro] object.
 * It's the glue between the CLI and platform-specific [Driver]s (encapsulated in the [Maestro] object).
 * It's one of the core classes in this codebase.
 *
 * Orchestra should not know about:
 *  - Specific platforms where tests can be executed, such as Android, iOS, or the web.
 *  - File systems. It should instead write to [Sink]s that it requests from the caller.
 */
class Orchestra(
    private val maestro: Maestro,
    private val screenshotsDir: File? = null, // TODO(bartekpacia): Orchestra shouldn't interact with files directly.
    private val lookupTimeoutMs: Long = 17000L,
    private val optionalLookupTimeoutMs: Long = 7000L,
    private val httpClient: OkHttpClient? = null,
    private val insights: Insights = NoopInsights,
    private val onFlowStart: (List<MaestroCommand>) -> Unit = {},
    private val onCommandStart: (Int, MaestroCommand) -> Unit = { _, _ -> },
    private val onCommandComplete: (Int, MaestroCommand) -> Unit = { _, _ -> },
    private val onCommandFailed: (Int, MaestroCommand, Throwable) -> ErrorResolution = { _, _, e -> throw e },
    private val onCommandWarned: (Int, MaestroCommand) -> Unit = { _, _ -> },
    private val onCommandSkipped: (Int, MaestroCommand) -> Unit = { _, _ -> },
    private val onCommandReset: (MaestroCommand) -> Unit = {},
    private val onCommandMetadataUpdate: (MaestroCommand, CommandMetadata) -> Unit = { _, _ -> },
    private val onCommandGeneratedOutput: (command: Command, defects: List<Defect>, screenshot: Buffer) -> Unit = { _, _, _ -> },
) {

    private lateinit var jsEngine: JsEngine

    private val ai: AI? = initAI()

    private var copiedText: String? = null

    private var timeMsOfLastInteraction = System.currentTimeMillis()

    private var screenRecording: ScreenRecording? = null

    private val rawCommandToMetadata = mutableMapOf<MaestroCommand, CommandMetadata>()

    fun runFlow(commands: List<MaestroCommand>): Boolean {
        timeMsOfLastInteraction = System.currentTimeMillis()

        val config = YamlCommandReader.getConfig(commands)

        initJsEngine(config)
        initAndroidChromeDevTools(config)

        onFlowStart(commands)

        executeDefineVariablesCommands(commands, config)
        // filter out DefineVariablesCommand to not execute it twice
        val filteredCommands = commands.filter { it.asCommand() !is DefineVariablesCommand }

        var flowSuccess = false
        var exception: Throwable? = null
        try {
            val onStartSuccess = config?.onFlowStart?.commands?.let {
                executeCommands(
                    commands = it,
                    config = config,
                    shouldReinitJsEngine = false,
                )
            } ?: true

            if (onStartSuccess) {
                flowSuccess = executeCommands(
                    commands = filteredCommands,
                    config = config,
                    shouldReinitJsEngine = false,
                ).also {
                    // close existing screen recording, if left open.
                    screenRecording?.close()
                }
            }
        } catch (e: Throwable) {
            exception = e
        } finally {
            val onCompleteSuccess = config?.onFlowComplete?.commands?.let {
                executeCommands(
                    commands = it,
                    config = config,
                    shouldReinitJsEngine = false,
                )
            } ?: true

            exception?.let { throw it }

            return onCompleteSuccess && flowSuccess
        }
    }

    fun executeCommands(
        commands: List<MaestroCommand>,
        config: MaestroConfig? = null,
        shouldReinitJsEngine: Boolean = true,
    ): Boolean {
        if (shouldReinitJsEngine) {
            initJsEngine(config)
        }

        initAndroidChromeDevTools(config)

        commands
            .forEachIndexed { index, command ->
                onCommandStart(index, command)

                jsEngine.onLogMessage { msg ->
                    val metadata = getMetadata(command)
                    updateMetadata(
                        command,
                        metadata.copy(logMessages = metadata.logMessages + msg)
                    )
                    logger.info("JsConsole: $msg")
                }

                val evaluatedCommand = command.evaluateScripts(jsEngine)
                val metadata = getMetadata(command)
                    .copy(
                        evaluatedCommand = evaluatedCommand,
                    )
                updateMetadata(command, metadata)

                val callback: (Insight) -> Unit = { insight ->
                    updateMetadata(
                        command,
                        getMetadata(command).copy(
                            insight = insight
                        )
                    )
                }
                insights.onInsightsUpdated(callback)

                try {
                    try {
                        executeCommand(evaluatedCommand, config)
                        onCommandComplete(index, command)
                    } catch (e: MaestroException) {
                        val isOptional =
                            command.asCommand()?.optional == true || command.elementSelector()?.optional == true
                        if (isOptional) throw CommandWarned(e.message)
                        else throw e
                    }
                } catch (ignored: CommandWarned) {
                    // Swallow exception, but add a warning as an insight
                    insights.report(Insight(message = ignored.message, level = Insight.Level.WARNING))
                    onCommandWarned(index, command)
                } catch (ignored: CommandSkipped) {
                    // Swallow exception
                    onCommandSkipped(index, command)
                } catch (e: Throwable) {
                    val errorResolution = onCommandFailed(index, command, e)
                    when (errorResolution) {
                        ErrorResolution.FAIL -> return false
                        ErrorResolution.CONTINUE -> {} // Do nothing
                    }
                } finally {
                    insights.unregisterListener(callback)
                }
            }
        return true
    }

    @Synchronized
    private fun initJsEngine(config: MaestroConfig?) {
        if (this::jsEngine.isInitialized) {
            jsEngine.close()
        }
        val shouldUseGraalJs =
            config?.ext?.get("jsEngine") == "graaljs" || System.getenv("MAESTRO_USE_GRAALJS") == "true"
        val platform = maestro.cachedDeviceInfo.platform.toString().lowercase()
        jsEngine = if (shouldUseGraalJs) {
            httpClient?.let { GraalJsEngine(it, platform) } ?: GraalJsEngine(platform = platform)
        } else {
            httpClient?.let { RhinoJsEngine(it, platform) } ?: RhinoJsEngine(platform = platform)
        }
    }

    private fun initAndroidChromeDevTools(config: MaestroConfig?) {
        val shouldEnableAndroidChromeDevTools = config?.ext?.get("androidWebViewHierarchy") == "devtools"
        maestro.setAndroidChromeDevToolsEnabled(shouldEnableAndroidChromeDevTools)
    }

    private fun initAI(): AI? {
        val apiKey = System.getenv(AI_KEY_ENV_VAR) ?: return null
        val modelName: String? = System.getenv(AI.AI_MODEL_ENV_VAR)

        return if (modelName == null) OpenAI(apiKey = apiKey)
        else if (modelName.startsWith("gpt-")) OpenAI(apiKey = apiKey, defaultModel = modelName)
        else if (modelName.startsWith("claude-")) Claude(apiKey = apiKey, defaultModel = modelName)
        else throw IllegalStateException("Unsupported AI model: $modelName")
    }

    /**
     * Returns true if the command mutated device state (i.e. interacted with the device), false otherwise.
     */
    private fun executeCommand(maestroCommand: MaestroCommand, config: MaestroConfig?): Boolean {
        val command = maestroCommand.asCommand()

        return when (command) {
            is TapOnElementCommand -> {
                tapOnElement(
                    command = command,
                    retryIfNoChange = command.retryIfNoChange ?: true,
                    waitUntilVisible = command.waitUntilVisible ?: false,
                    config = config
                )
            }

            is TapOnPointCommand -> tapOnPoint(command, command.retryIfNoChange ?: true)
            is TapOnPointV2Command -> tapOnPointV2Command(command)
            is BackPressCommand -> backPressCommand()
            is HideKeyboardCommand -> hideKeyboardCommand()
            is ScrollCommand -> scrollVerticalCommand()
            is CopyTextFromCommand -> copyTextFromCommand(command)
            is ScrollUntilVisibleCommand -> scrollUntilVisible(command)
            is PasteTextCommand -> pasteText()
            is SwipeCommand -> swipeCommand(command)
            is AssertCommand -> assertCommand(command)
            is AssertConditionCommand -> assertConditionCommand(command)
            is AssertNoDefectsWithAICommand -> assertNoDefectsWithAICommand(command)
            is AssertWithAICommand -> assertWithAICommand(command)
            is ExtractTextWithAICommand -> extractTextWithAICommand(command)
            is InputTextCommand -> inputTextCommand(command)
            is InputRandomCommand -> inputTextRandomCommand(command)
            is LaunchAppCommand -> launchAppCommand(command)
            is OpenLinkCommand -> openLinkCommand(command, config)
            is PressKeyCommand -> pressKeyCommand(command)
            is EraseTextCommand -> eraseTextCommand(command)
            is TakeScreenshotCommand -> takeScreenshotCommand(command)
            is StopAppCommand -> stopAppCommand(command)
            is KillAppCommand -> killAppCommand(command)
            is ClearStateCommand -> clearAppStateCommand(command)
            is ClearKeychainCommand -> clearKeychainCommand()
            is RunFlowCommand -> runFlowCommand(command, config)
            is SetLocationCommand -> setLocationCommand(command)
            is RepeatCommand -> repeatCommand(command, maestroCommand, config)
            is DefineVariablesCommand -> defineVariablesCommand(command)
            is RunScriptCommand -> runScriptCommand(command)
            is EvalScriptCommand -> evalScriptCommand(command)
            is ApplyConfigurationCommand -> false
            is WaitForAnimationToEndCommand -> waitForAnimationToEndCommand(command)
            is TravelCommand -> travelCommand(command)
            is StartRecordingCommand -> startRecordingCommand(command)
            is StopRecordingCommand -> stopRecordingCommand()
            is AddMediaCommand -> addMediaCommand(command.mediaPaths)
            is SetAirplaneModeCommand -> setAirplaneMode(command)
            is ToggleAirplaneModeCommand -> toggleAirplaneMode()
            is RetryCommand -> retryCommand(command, config)
            else -> true
        }.also { mutating ->
            if (mutating) {
                timeMsOfLastInteraction = System.currentTimeMillis()
            }
        }
    }

    private fun setAirplaneMode(command: SetAirplaneModeCommand): Boolean {
        when (command.value) {
            AirplaneValue.Enable -> maestro.setAirplaneModeState(true)
            AirplaneValue.Disable -> maestro.setAirplaneModeState(false)
        }

        return true
    }

    private fun toggleAirplaneMode(): Boolean {
        maestro.setAirplaneModeState(!maestro.isAirplaneModeEnabled())
        return true
    }

    private fun travelCommand(command: TravelCommand): Boolean {
        Traveller.travel(
            maestro = maestro,
            points = command.points,
            speedMPS = command.speedMPS ?: 4.0,
        )

        return true
    }

    private fun addMediaCommand(mediaPaths: List<String>): Boolean {
        maestro.addMedia(mediaPaths)
        return true
    }

    private fun assertConditionCommand(command: AssertConditionCommand): Boolean {
        val timeout = (command.timeoutMs() ?: lookupTimeoutMs)
        if (!evaluateCondition(command.condition, timeoutMs = timeout, commandOptional = command.optional)) {
            throw MaestroException.AssertionFailure(
                message = "Assertion is false: ${command.condition.description()}",
                hierarchyRoot = maestro.viewHierarchy().root,
            )
        }

        return false
    }

    private fun assertNoDefectsWithAICommand(command: AssertNoDefectsWithAICommand): Boolean = runBlocking {
        // TODO(bartekpacia): make all of Orchestra suspending
        val apiKey = System.getenv("MAESTRO_CLOUD_API_KEY")
        if (apiKey.isNullOrEmpty()) {
            throw MaestroException.CloudApiKeyNotAvailable("`MAESTRO_CLOUD_API_KEY` is not available. Did you export MAESTRO_CLOUD_API_KEY?")
        }

        val imageData = Buffer()
        maestro.takeScreenshot(imageData, compressed = false)

        val defects = Prediction.findDefects(
            apiKey = apiKey,
            screen = imageData.copy().readByteArray(),
        )

        if (defects.isNotEmpty()) {
            onCommandGeneratedOutput(command, defects, imageData)

            val word = if (defects.size == 1) "defect" else "defects"
            throw MaestroException.AssertionFailure(
                message = """
                    |Found ${defects.size} possible $word:
                    ${defects.joinToString("\n") { "| - ${it.reasoning}" }}
                    |
                    """.trimMargin(),
                hierarchyRoot = maestro.viewHierarchy().root,
            )
        }

        false
    }

    private fun assertWithAICommand(command: AssertWithAICommand): Boolean = runBlocking {
        // TODO(bartekpacia): make all of Orchestra suspending
        val apiKey = System.getenv("MAESTRO_CLOUD_API_KEY")
        if (apiKey.isNullOrEmpty()) {
            throw MaestroException.CloudApiKeyNotAvailable("`MAESTRO_CLOUD_API_KEY` is not available. Did you export MAESTRO_CLOUD_API_KEY?")
        }

        val imageData = Buffer()
        maestro.takeScreenshot(imageData, compressed = false)

        val defect = Prediction.performAssertion(
            apiKey = apiKey,
            assertion = command.assertion,
            screen = imageData.copy().readByteArray(),
        )

        if (defect != null) {
            onCommandGeneratedOutput(command, listOf(defect), imageData)
            throw MaestroException.AssertionFailure(
                message = """
                    |Assertion "${command.assertion}" is false.
                    |Reasoning: ${defect.reasoning}
                    """.trimMargin(),
                hierarchyRoot = maestro.viewHierarchy().root,
            )
        }

        false
    }

    private fun extractTextWithAICommand(command: ExtractTextWithAICommand): Boolean = runBlocking {
        val apiKey = System.getenv("MAESTRO_CLOUD_API_KEY")
        if (apiKey.isNullOrEmpty()) {
            throw MaestroException.CloudApiKeyNotAvailable("`MAESTRO_CLOUD_API_KEY` is not available. Did you export MAESTRO_CLOUD_API_KEY?")
        }

        val imageData = Buffer()
        maestro.takeScreenshot(imageData, compressed = false)
        val text = Prediction.extractText(
            apiKey = apiKey,
            query = command.query,
            screen = imageData.copy().readByteArray(),
        )

        jsEngine.putEnv(command.outputVariable, text)

        false
    }

    private fun evalScriptCommand(command: EvalScriptCommand): Boolean {
        command.scriptString.evaluateScripts(jsEngine)

        // We do not actually know if there were any mutations, but we assume there were
        return true
    }

    private fun runScriptCommand(command: RunScriptCommand): Boolean {
        return if (evaluateCondition(command.condition, commandOptional = command.optional)) {
            jsEngine.evaluateScript(
                script = command.script,
                env = command.env,
                sourceName = command.sourceDescription,
                runInSubScope = true,
            )

            // We do not actually know if there were any mutations, but we assume there were
            true
        } else {
            throw CommandSkipped
        }
    }

    private fun waitForAnimationToEndCommand(command: WaitForAnimationToEndCommand): Boolean {
        maestro.waitForAnimationToEnd(command.timeout)

        return true
    }

    private fun defineVariablesCommand(command: DefineVariablesCommand): Boolean {
        command.env.forEach { (name, value) ->
            jsEngine.putEnv(name, value)
        }

        return false
    }

    private fun setLocationCommand(command: SetLocationCommand): Boolean {
        maestro.setLocation(command.latitude, command.longitude)

        return true
    }

    private fun clearAppStateCommand(command: ClearStateCommand): Boolean {
        maestro.clearAppState(command.appId)
        // Android's clear command also resets permissions
        // Reset all permissions to unset so both platforms behave the same
        maestro.setPermissions(command.appId, mapOf("all" to "unset"))

        return true
    }

    private fun stopAppCommand(command: StopAppCommand): Boolean {
        maestro.stopApp(command.appId)

        return true
    }

    private fun killAppCommand(command: KillAppCommand): Boolean {
        maestro.killApp(command.appId)

        return true
    }

    private fun scrollVerticalCommand(): Boolean {
        maestro.scrollVertical()
        return true
    }

    private fun scrollUntilVisible(command: ScrollUntilVisibleCommand): Boolean {
        val endTime = System.currentTimeMillis() + command.timeout.toLong()
        val direction = command.direction.toSwipeDirection()
        val deviceInfo = maestro.deviceInfo()

        var retryCenterCount = 0
        val maxRetryCenterCount =
            4 // for when the list is no longer scrollable (last element) but the element is visible

        do {
            try {
                val element = findElement(command.selector, command.optional, 500).element
                val visibility = element.getVisiblePercentage(deviceInfo.widthGrid, deviceInfo.heightGrid)

                logger.info("Scrolling try count: $retryCenterCount, DeviceWidth: ${deviceInfo.widthGrid}, DeviceWidth: ${deviceInfo.heightGrid}")
                logger.info("Element bounds: ${element.bounds}")
                logger.info("Visibility Percent: $retryCenterCount")
                logger.info("Command centerElement: $command.centerElement")
                logger.info("visibilityPercentageNormalized: ${command.visibilityPercentageNormalized}")

                if (command.centerElement && visibility > 0.1 && retryCenterCount <= maxRetryCenterCount) {
                    if (element.isElementNearScreenCenter(direction, deviceInfo.widthGrid, deviceInfo.heightGrid)) {
                        return true
                    }
                    retryCenterCount++
                } else if (visibility >= command.visibilityPercentageNormalized) {
                    return true
                }
            } catch (ignored: MaestroException.ElementNotFound) {
              logger.error("Error: $ignored")
            }
            maestro.swipeFromCenter(direction, durationMs = command.scrollDuration.toLong(), waitToSettleTimeoutMs = command.waitToSettleTimeoutMs)
        } while (System.currentTimeMillis() < endTime)

        throw MaestroException.ElementNotFound(
            "No visible element found: ${command.selector.description()}",
            maestro.viewHierarchy().root
        )
    }

    private fun hideKeyboardCommand(): Boolean {
        maestro.hideKeyboard()
        return true
    }

    private fun backPressCommand(): Boolean {
        maestro.backPress()
        return true
    }

    private fun repeatCommand(command: RepeatCommand, maestroCommand: MaestroCommand, config: MaestroConfig?): Boolean {
        val maxRuns = command.times?.toDoubleOrNull()?.toInt() ?: Int.MAX_VALUE

        var counter = 0
        var metadata = getMetadata(maestroCommand)
        metadata = metadata.copy(
            numberOfRuns = 0,
        )

        var mutatiing = false

        val checkCondition: () -> Boolean = {
            command.condition
                ?.evaluateScripts(jsEngine)
                ?.let { evaluateCondition(it, commandOptional = command.optional) } != false
        }

        while (checkCondition() && counter < maxRuns) {
            if (counter > 0) {
                command.commands.forEach { resetCommand(it) }
            }

            val mutated = runSubFlow(command.commands, config, null)
            mutatiing = mutatiing || mutated
            counter++

            metadata = metadata.copy(
                numberOfRuns = counter,
            )
            updateMetadata(maestroCommand, metadata)
        }

        if (counter == 0) {
            throw CommandSkipped
        }

        return mutatiing
    }

    private fun retryCommand(command: RetryCommand, config: MaestroConfig?): Boolean {
        val maxRetries = (command.maxRetries?.toIntOrNull() ?: 1).coerceAtMost(MAX_RETRIES_ALLOWED)

        var attempt = 0
        while (attempt <= maxRetries) {
            try {
                return runSubFlow(command.commands, config, command.config)
            } catch (exception: Throwable) {
                if (attempt == maxRetries) {
                    logger.error("Max retries ($maxRetries) reached. Commands failed.", exception)
                    throw exception
                }

                val message =
                    "Retrying the commands due to an error: ${exception.message} while execution (Attempt ${attempt + 1})"
                logger.error("Attempt ${attempt + 1} failed for retry command", exception)
                insights.report(Insight(message = message, Insight.Level.WARNING))
            }
            attempt++
        }

        return false
    }

    private fun updateMetadata(rawCommand: MaestroCommand, metadata: CommandMetadata) {
        rawCommandToMetadata[rawCommand] = metadata
        onCommandMetadataUpdate(rawCommand, metadata)
    }

    private fun getMetadata(rawCommand: MaestroCommand) = rawCommandToMetadata.getOrPut(rawCommand) {
        CommandMetadata()
    }

    private fun resetCommand(command: MaestroCommand) {
        onCommandReset(command)

        (command.asCommand() as? CompositeCommand)?.let {
            it.subCommands().forEach { command ->
                resetCommand(command)
            }
        }
    }

    private fun runFlowCommand(command: RunFlowCommand, config: MaestroConfig?): Boolean {
        return if (evaluateCondition(command.condition, command.optional)) {
            runSubFlow(command.commands, config, command.config)
        } else {
            throw CommandSkipped
        }
    }

    private fun evaluateCondition(
        condition: Condition?,
        commandOptional: Boolean,
        timeoutMs: Long? = null,
    ): Boolean {
        if (condition == null) {
            return true
        }

        condition.platform?.let {
            if (it != maestro.cachedDeviceInfo.platform) {
                return false
            }
        }

        condition.visible?.let {
            try {
                findElement(
                    selector = it,
                    timeoutMs = adjustedToLatestInteraction(timeoutMs ?: optionalLookupTimeoutMs),
                    optional = commandOptional,
                )
            } catch (ignored: MaestroException.ElementNotFound) {
                return false
            }
        }

        condition.notVisible?.let {
            val result = MaestroTimer.withTimeout(adjustedToLatestInteraction(timeoutMs ?: optionalLookupTimeoutMs)) {
                try {
                    findElement(
                        selector = it,
                        timeoutMs = 500L,
                        optional = commandOptional,
                    )

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

        condition.scriptCondition?.let { value ->
            // Note that script should have been already evaluated by this point

            if (value.isBlank()) {
                return false
            }

            if (value.equals("false", ignoreCase = true)) {
                return false
            }

            if (value == "undefined") {
                return false
            }

            if (value == "null") {
                return false
            }

            if (value.toDoubleOrNull() == 0.0) {
                return false
            }
        }

        return true
    }

    private fun executeSubflowCommands(commands: List<MaestroCommand>, config: MaestroConfig?): Boolean {
        jsEngine.enterScope()

        return try {
            commands
                .mapIndexed { index, command ->
                    onCommandStart(index, command)

                    val evaluatedCommand = command.evaluateScripts(jsEngine)
                    val metadata = getMetadata(command)
                        .copy(
                            evaluatedCommand = evaluatedCommand,
                        )
                    updateMetadata(command, metadata)

                    return@mapIndexed try {
                        try {
                            executeCommand(evaluatedCommand, config)
                                .also {
                                    onCommandComplete(index, command)
                                }
                        } catch (exception: MaestroException) {
                            val isOptional =
                                command.asCommand()?.optional == true || command.elementSelector()?.optional == true
                            if (isOptional) throw CommandWarned(exception.message)
                            else throw exception
                        }
                    } catch (ignored: CommandWarned) {
                        // Swallow exception, but add a warning as an insight
                        insights.report(Insight(message = ignored.message, level = Insight.Level.WARNING))
                        onCommandWarned(index, command)
                        false
                    } catch (ignored: CommandSkipped) {
                        // Swallow exception
                        onCommandSkipped(index, command)
                        false
                    } catch (e: Throwable) {
                        when (onCommandFailed(index, command, e)) {
                            ErrorResolution.FAIL -> throw e
                            ErrorResolution.CONTINUE -> {
                                // Do nothing
                                false
                            }
                        }
                    }
                }
                .any { it }
        } finally {
            jsEngine.leaveScope()
        }
    }

    private fun runSubFlow(
        commands: List<MaestroCommand>,
        config: MaestroConfig?,
        subflowConfig: MaestroConfig?,
    ): Boolean {
        executeDefineVariablesCommands(commands, config)
        // filter out DefineVariablesCommand to not execute it twice
        val filteredCommands = commands.filter { it.asCommand() !is DefineVariablesCommand }

        var flowSuccess = false
        val onCompleteSuccess: Boolean
        try {
            val onStartSuccess = subflowConfig?.onFlowStart?.commands?.let {
                executeSubflowCommands(it, config)
            } ?: true

            if (onStartSuccess) {
                flowSuccess = executeSubflowCommands(filteredCommands, config)
            }
        } catch (e: Throwable) {
            throw e
        } finally {
            onCompleteSuccess = subflowConfig?.onFlowComplete?.commands?.let {
                executeSubflowCommands(it, config)
            } ?: true
        }
        return onCompleteSuccess && flowSuccess
    }

    private fun takeScreenshotCommand(command: TakeScreenshotCommand): Boolean {
        val pathStr = command.path + ".png"
        val file = screenshotsDir
            ?.let { File(it, pathStr) }
            ?: File(pathStr)

        maestro.takeScreenshot(file, false)

        return false
    }

    private fun startRecordingCommand(command: StartRecordingCommand): Boolean {
        val pathStr = command.path + ".mp4"
        val file = screenshotsDir
            ?.let { File(it, pathStr) }
            ?: File(pathStr)
        screenRecording = maestro.startScreenRecording(file.sink().buffer())
        return false
    }

    private fun stopRecordingCommand(): Boolean {
        screenRecording?.close()
        return false
    }

    private fun eraseTextCommand(command: EraseTextCommand): Boolean {
        val charactersToErase = command.charactersToErase
        maestro.eraseText(charactersToErase ?: MAX_ERASE_CHARACTERS)
        maestro.waitForAppToSettle()

        return true
    }

    private fun pressKeyCommand(command: PressKeyCommand): Boolean {
        maestro.pressKey(command.code)

        return true
    }

    private fun openLinkCommand(command: OpenLinkCommand, config: MaestroConfig?): Boolean {
        maestro.openLink(command.link, config?.appId, command.autoVerify ?: false, command.browser ?: false)

        return true
    }

    private fun launchAppCommand(command: LaunchAppCommand): Boolean {
        try {
            if (command.clearKeychain == true) {
                maestro.clearKeychain()
            }
            if (command.clearState == true) {
                maestro.clearAppState(command.appId)
            }

            // For testing convenience, default to allow all on app launch
            val permissions = command.permissions ?: mapOf("all" to "allow")
            maestro.setPermissions(command.appId, permissions)

        } catch (e: Exception) {
            throw MaestroException.UnableToClearState("Unable to clear state for app ${command.appId}: ${e.message}")
        }

        try {
            maestro.launchApp(
                appId = command.appId,
                launchArguments = command.launchArguments ?: emptyMap(),
                stopIfRunning = command.stopApp ?: true
            )
        } catch (e: Exception) {
            throw MaestroException.UnableToLaunchApp("Unable to launch app ${command.appId}: ${e.message}")
        }

        return true
    }

    private fun clearKeychainCommand(): Boolean {
        maestro.clearKeychain()

        return true
    }

    private fun inputTextCommand(command: InputTextCommand): Boolean {
        if (!maestro.isUnicodeInputSupported()) {
            val isAscii = Charsets.US_ASCII.newEncoder()
                .canEncode(command.text)

            if (!isAscii) {
                throw UnicodeNotSupportedError(command.text)
            }
        }

        maestro.inputText(command.text)

        return true
    }

    private fun inputTextRandomCommand(command: InputRandomCommand): Boolean {
        inputTextCommand(InputTextCommand(text = command.genRandomString()))

        return true
    }

    private fun assertCommand(command: AssertCommand): Boolean {
        return assertConditionCommand(
            command.toAssertConditionCommand()
        )
    }

    private fun tapOnElement(
        command: TapOnElementCommand,
        retryIfNoChange: Boolean,
        waitUntilVisible: Boolean,
        config: MaestroConfig?,
    ): Boolean {
        val result = findElement(command.selector, optional = command.optional)

        maestro.tap(
            element = result.element,
            initialHierarchy = result.hierarchy,
            retryIfNoChange = retryIfNoChange,
            waitUntilVisible = waitUntilVisible,
            longPress = command.longPress ?: false,
            appId = config?.appId,
            tapRepeat = command.repeat,
            waitToSettleTimeoutMs = command.waitToSettleTimeoutMs,
        )

        return true
    }

    private fun tapOnPoint(
        command: TapOnPointCommand,
        retryIfNoChange: Boolean,
    ): Boolean {
        maestro.tap(
            x = command.x,
            y = command.y,
            retryIfNoChange = retryIfNoChange,
            longPress = command.longPress ?: false,
            tapRepeat = command.repeat,
        )

        return true
    }

    private fun tapOnPointV2Command(
        command: TapOnPointV2Command,
    ): Boolean {
        val point = command.point

        if (point.contains("%")) {
            val (percentX, percentY) = point
                .replace("%", "")
                .split(",")
                .map { it.trim().toInt() }

            if (percentX !in 0..100 || percentY !in 0..100) {
                throw MaestroException.InvalidCommand("Invalid point: $point")
            }

            maestro.tapOnRelative(
                percentX = percentX,
                percentY = percentY,
                retryIfNoChange = command.retryIfNoChange ?: true,
                longPress = command.longPress ?: false,
                tapRepeat = command.repeat,
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
            )
        } else {
            val (x, y) = point.split(",")
                .map {
                    it.trim().toInt()
                }

            maestro.tap(
                x = x,
                y = y,
                retryIfNoChange = command.retryIfNoChange ?: true,
                longPress = command.longPress ?: false,
                tapRepeat = command.repeat,
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
            )
        }

        return true
    }

    private fun findElement(
        selector: ElementSelector,
        optional: Boolean,
        timeoutMs: Long? = null,
    ): FindElementResult {
        val timeout =
            timeoutMs ?: adjustedToLatestInteraction(
                if (optional) optionalLookupTimeoutMs
                else lookupTimeoutMs,
            )

        val (description, filterFunc) = buildFilter(selector = selector)
        if (selector.childOf != null) {
            val parentViewHierarchy = findElementViewHierarchy(
                selector.childOf,
                timeout
            )
            return maestro.findElementWithTimeout(
                timeout,
                filterFunc,
                parentViewHierarchy
            ) ?: throw MaestroException.ElementNotFound(
                "Element not found: $description",
                parentViewHierarchy.root,
            )
        }


        return maestro.findElementWithTimeout(
            timeoutMs = timeout,
            filter = filterFunc
        ) ?: throw MaestroException.ElementNotFound(
            "Element not found: $description",
            maestro.viewHierarchy().root,
        )
    }

    private fun findElementViewHierarchy(
        selector: ElementSelector?,
        timeout: Long
    ): ViewHierarchy {
        if (selector == null) {
            return maestro.viewHierarchy()
        }
        val parentViewHierarchy = findElementViewHierarchy(selector.childOf, timeout)
        val (description, filterFunc) = buildFilter(selector = selector)
        return maestro.findElementWithTimeout(
            timeout,
            filterFunc,
            parentViewHierarchy
        )?.hierarchy ?: throw MaestroException.ElementNotFound(
            "Element not found: $description",
            parentViewHierarchy.root,
        )
    }

    private fun buildFilter(
        selector: ElementSelector,
    ): FilterWithDescription {
        val filters = mutableListOf<ElementFilter>()
        val descriptions = mutableListOf<String>()

        selector.textRegex
            ?.let {
                descriptions += "Text matching regex: $it"
                filters += Filters.deepestMatchingElement(
                    Filters.textMatches(it.toRegexSafe(REGEX_OPTIONS))
                )
            }

        selector.idRegex
            ?.let {
                descriptions += "Id matching regex: $it"
                filters += Filters.deepestMatchingElement(
                    Filters.idMatches(it.toRegexSafe(REGEX_OPTIONS))
                )
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
                filters += Filters.below(buildFilter(it).filterFunc)
            }

        selector.above
            ?.let {
                descriptions += "Above: ${it.description()}"
                filters += Filters.above(buildFilter(it).filterFunc)
            }

        selector.leftOf
            ?.let {
                descriptions += "Left of: ${it.description()}"
                filters += Filters.leftOf(buildFilter(it).filterFunc)
            }

        selector.rightOf
            ?.let {
                descriptions += "Right of: ${it.description()}"
                filters += Filters.rightOf(buildFilter(it).filterFunc)
            }

        selector.containsChild
            ?.let {
                descriptions += "Contains child: ${it.description()}"
                filters += Filters.containsChild(findElement(it, optional = false).element).asFilter()
            }

        selector.containsDescendants
            ?.let { descendantSelectors ->
                val descendantDescriptions = descendantSelectors.joinToString("; ") { it.description() }
                descriptions += "Contains descendants: $descendantDescriptions"
                filters += Filters.containsDescendants(descendantSelectors.map { buildFilter(it).filterFunc })
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

        selector.selected
            ?.let {
                descriptions += if (it) {
                    "Selected"
                } else {
                    "Not selected"
                }
                filters += Filters.selected(it)
            }

        selector.checked
            ?.let {
                descriptions += if (it) {
                    "Checked"
                } else {
                    "Not checked"
                }
                filters += Filters.checked(it)
            }

        selector.focused
            ?.let {
                descriptions += if (it) {
                    "Focused"
                } else {
                    "Not focused"
                }
                filters += Filters.focused(it)
            }

        var resultFilter = Filters.intersect(filters)
        resultFilter = selector.index
            ?.toDouble()
            ?.toInt()
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

    private fun swipeCommand(command: SwipeCommand): Boolean {
        val elementSelector = command.elementSelector
        val direction = command.direction
        val startRelative = command.startRelative
        val endRelative = command.endRelative
        val start = command.startPoint
        val end = command.endPoint
        when {
            elementSelector != null && direction != null -> {
                val uiElement = findElement(elementSelector, optional = command.optional)
                maestro.swipe(direction, uiElement.element, command.duration, waitToSettleTimeoutMs = command.waitToSettleTimeoutMs)
            }

            startRelative != null && endRelative != null -> {
                maestro.swipe(startRelative = startRelative, endRelative = endRelative, duration = command.duration, waitToSettleTimeoutMs = command.waitToSettleTimeoutMs)
            }

            direction != null -> maestro.swipe(swipeDirection = direction, duration = command.duration, waitToSettleTimeoutMs = command.waitToSettleTimeoutMs)
            start != null && end != null -> maestro.swipe(
                startPoint = start,
                endPoint = end,
                duration = command.duration,
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
            )

            else -> error("Illegal arguments for swiping")
        }
        return true
    }

    private fun adjustedToLatestInteraction(timeMs: Long) = max(
        0,
        timeMs - (System.currentTimeMillis() - timeMsOfLastInteraction),
    )

    private fun copyTextFromCommand(command: CopyTextFromCommand): Boolean {
        val result = findElement(command.selector, optional = command.optional)
        copiedText = resolveText(result.element.treeNode.attributes)
            ?: throw MaestroException.UnableToCopyTextFromElement("Element does not contain text to copy: ${result.element}")

        jsEngine.setCopiedText(copiedText)

        return true
    }

    private fun resolveText(attributes: MutableMap<String, String>): String? {
        return if (!attributes["text"].isNullOrEmpty()) {
            attributes["text"]
        } else if (!attributes["hintText"].isNullOrEmpty()) {
            attributes["hintText"]
        } else {
            attributes["accessibilityText"]
        }
    }

    private fun pasteText(): Boolean {
        copiedText?.let { maestro.inputText(it) }
        return true
    }

    private fun executeDefineVariablesCommands(commands: List<MaestroCommand>, config: MaestroConfig?) {
        commands.filter { it.asCommand() is DefineVariablesCommand }.takeIf { it.isNotEmpty() }?.let {
            executeCommands(
                commands = it,
                config = config,
                shouldReinitJsEngine = false
            )
        }
    }

    private object CommandSkipped : Exception()

    class CommandWarned(override val message: String) : Exception(message)

    data class CommandMetadata(
        val numberOfRuns: Int? = null,
        val evaluatedCommand: MaestroCommand? = null,
        val logMessages: List<String> = emptyList(),
        val insight: Insight = Insight("", Insight.Level.NONE),
    )

    enum class ErrorResolution {
        CONTINUE,
        FAIL
    }

    companion object {

        val REGEX_OPTIONS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)

        private const val MAX_ERASE_CHARACTERS = 50
        private const val MAX_RETRIES_ALLOWED = 3
        private val logger = LoggerFactory.getLogger(Orchestra::class.java)
    }
}
