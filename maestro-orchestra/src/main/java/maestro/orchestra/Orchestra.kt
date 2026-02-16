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

import kotlinx.coroutines.currentCoroutineContext
import ImageConcatenate
import com.github.romankh3.image.comparison.ImageComparison
import com.github.romankh3.image.comparison.model.ImageComparisonState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import maestro.Driver
import maestro.ElementFilter
import maestro.Filters
import maestro.Filters.asFilter
import maestro.FindElementResult
import maestro.Maestro
import maestro.MaestroException
import maestro.Point
import maestro.ScreenRecording
import maestro.UiElement
import maestro.ViewHierarchy
import maestro.ai.AI
import maestro.ai.AI.Companion.AI_KEY_ENV_VAR
import maestro.ai.cloud.Defect
import maestro.ai.CloudAIPredictionEngine
import maestro.ai.AIPredictionEngine
import maestro.ai.anthropic.Claude
import maestro.ai.openai.OpenAI
import maestro.js.GraalJsEngine
import maestro.js.JsEngine
import maestro.js.RhinoJsEngine
import maestro.orchestra.error.UnicodeNotSupportedError
import maestro.orchestra.filter.FilterWithDescription
import maestro.orchestra.filter.TraitFilters
import maestro.orchestra.geo.Traveller
import maestro.orchestra.util.ImageCropUtil
import maestro.orchestra.util.ViewHierarchySerializer
import maestro.orchestra.util.calculateElementRelativePoint
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
import okio.BufferedSink
import okio.Sink
import okio.buffer
import okio.sink
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.File
import java.lang.Long.max
import java.nio.file.Path
import java.util.logging.Filter
import javax.imageio.ImageIO
import kotlin.coroutines.coroutineContext

// TODO(bartkepacia): Use this in onCommandGeneratedOutput.
//  Caveat:
//    Large files should not be held in memory, instead they should be directly written to a Buffer
//    that is streamed to disk.
//  Idea:
//    Orchestra should expose a callback like "onResourceRequested: (Command, CommandOutputType)"

interface FlowController {
    suspend fun waitIfPaused()
    fun pause()
    fun resume()
    val isPaused: Boolean
}

class DefaultFlowController : FlowController {
    private var _isPaused = false

    override suspend fun waitIfPaused() {
        while (_isPaused) {
            if (!currentCoroutineContext().isActive) {
                break
            }
            Thread.sleep(500)
        }
    }

    override fun pause() {
        _isPaused = true
    }

    override fun resume() {
        _isPaused = false
    }

    override val isPaused: Boolean get() = _isPaused
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
    private val screenshotsDir: Path? = null, // TODO(bartekpacia): Orchestra shouldn't interact with files directly.
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
    private val AIPredictionEngine: AIPredictionEngine =  CloudAIPredictionEngine() ,
    private val flowController: FlowController = DefaultFlowController(),
) {

    private lateinit var jsEngine: JsEngine

    private val ai: AI = initAI()

    private var copiedText: String? = null

    private var timeMsOfLastInteraction = System.currentTimeMillis()

    private var screenRecording: ScreenRecording? = null

    private val rawCommandToMetadata = mutableMapOf<MaestroCommand, CommandMetadata>()

    suspend fun runFlow(commands: List<MaestroCommand>): Boolean {
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

    suspend fun evaluateCommands(
        commands: List<MaestroCommand>,
        shouldReinitJsEngine: Boolean = true,
    ): List<MaestroCommand> {
        val config = YamlCommandReader.getConfig(commands)
        if (shouldReinitJsEngine) {
            initJsEngine(config)
        }
        initAndroidChromeDevTools(config)
        executeDefineVariablesCommands(commands, config)
        // filter out DefineVariablesCommand to not execute it twice
        val filteredCommands = commands.filter { it.asCommand() !is DefineVariablesCommand }
        val evaluatedCommands = filteredCommands.map{ command ->
            jsEngine.onLogMessage { msg ->
                val metadata = getMetadata(command)
                updateMetadata(
                    command,
                    metadata.copy(logMessages = metadata.logMessages + msg)
                )
            }

            val evaluatedCommand = command.evaluateScripts(jsEngine)
            val metadata = getMetadata(command)
                .copy(
                    evaluatedCommand = evaluatedCommand,
                )
            updateMetadata(evaluatedCommand, metadata)
            evaluatedCommand
        }
        return evaluatedCommands
    }

    suspend fun executeCommands(
        commands: List<MaestroCommand>,
        config: MaestroConfig? = null,
        shouldReinitJsEngine: Boolean = true,
    ): Boolean {
        if (shouldReinitJsEngine) {
            initJsEngine(config)
        }

        if (!currentCoroutineContext().isActive) {
            logger.info("Flow cancelled, skipping initAndroidChromeDevTools...")
        } else {
            initAndroidChromeDevTools(config)
        }

        commands
            .forEachIndexed { index, command ->
                if (!currentCoroutineContext().isActive) {
                    logger.info("[Command execution] Command skipped due to cancellation: $command")
                    onCommandSkipped(index, command)
                    return@forEachIndexed
                }

                // Check for pause before executing each command
                flowController.waitIfPaused()

                val evaluatedCommand = command.evaluateScripts(jsEngine)
                val metadata = getMetadata(command)
                    .copy(
                        evaluatedCommand = evaluatedCommand,
                    )
                updateMetadata(command, metadata)

                onCommandStart(index, command)

                jsEngine.onLogMessage { msg ->
                    val metadata = getMetadata(command)
                    updateMetadata(
                        command,
                        metadata.copy(logMessages = metadata.logMessages + msg)
                    )
                    logger.info("JsConsole: $msg")
                }

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
                    logger.info("[Command execution] CommandWarned: ${ignored.message}")
                    // Swallow exception, but add a warning as an insight
                    insights.report(Insight(message = ignored.message, level = Insight.Level.WARNING))
                    onCommandWarned(index, command)
                } catch (ignored: CommandSkipped) {
                    logger.info("[Command execution] CommandSkipped: ${ignored.message}")
                    // Swallow exception
                    onCommandSkipped(index, command)
                } catch (e: Throwable) {
                    logger.error("[Command execution] CommandFailed: ${e.message}")
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
        val isRhinoExplicitlyRequested = config?.ext?.get("jsEngine") == "rhino"

        val platform = maestro.cachedDeviceInfo.platform.toString().lowercase()
        jsEngine = if (isRhinoExplicitlyRequested) {
            httpClient?.let { RhinoJsEngine(it, platform) } ?: RhinoJsEngine(platform = platform)
        } else {
            // Default to GraalJS for better performance and compatibility
            httpClient?.let { GraalJsEngine(it, platform) } ?: GraalJsEngine(platform = platform)
        }
    }

    private fun initAndroidChromeDevTools(config: MaestroConfig?) {
        if (config == null) return
        val shouldEnableAndroidChromeDevTools = config.ext["androidWebViewHierarchy"] == "devtools"
        maestro.setAndroidChromeDevToolsEnabled(shouldEnableAndroidChromeDevTools)
    }

    private fun initAI(): AI {
        logger.info("[Orchestra] Initializing AI")
        val apiKey = System.getenv(AI_KEY_ENV_VAR)
        val modelName: String? = System.getenv(AI.AI_MODEL_ENV_VAR)

        return if (modelName == null) OpenAI(apiKey = apiKey)
        else if (modelName.startsWith("gpt-")) OpenAI(apiKey = apiKey, defaultModel = modelName)
        else if (modelName.startsWith("claude-")) Claude(apiKey = apiKey, defaultModel = modelName)
        else throw IllegalStateException("Unsupported AI model: $modelName")
    }

    /**
     * Returns true if the command mutated device state (i.e. interacted with the device), false otherwise.
     */
    private suspend fun executeCommand(maestroCommand: MaestroCommand, config: MaestroConfig?): Boolean {
        val command = maestroCommand.asCommand()

        if (!currentCoroutineContext().isActive) {
            throw CommandSkipped
        }

        flowController.waitIfPaused()

        return when (command) {
            is TapOnElementCommand -> {
                tapOnElement(
                    command = command,
                    retryIfNoChange = command.retryIfNoChange ?: false,
                    waitUntilVisible = command.waitUntilVisible ?: false,
                    config = config
                )
            }

            is TapOnPointCommand -> tapOnPoint(command, command.retryIfNoChange ?: false)
            is TapOnPointV2Command -> tapOnPointV2Command(command)
            is BackPressCommand -> backPressCommand()
            is HideKeyboardCommand -> hideKeyboardCommand()
            is ScrollCommand -> scrollVerticalCommand()
            is CopyTextFromCommand -> copyTextFromCommand(command)
            is SetClipboardCommand -> setClipboardCommand(command)
            is ScrollUntilVisibleCommand -> scrollUntilVisible(command)
            is PasteTextCommand -> pasteText()
            is SwipeCommand -> swipeCommand(command)
            is AssertCommand -> assertCommand(command)
            is AssertVisualCommand -> assertVisualCommand(command)
            is AssertConditionCommand -> assertConditionCommand(command)
            is AssertNoDefectsWithAICommand -> assertNoDefectsWithAICommand(command, maestroCommand)
            is AssertWithAICommand -> assertWithAICommand(command, maestroCommand)
            is ExtractTextWithAICommand -> extractTextWithAICommand(command, maestroCommand)
            is ExtractPointWithAICommand -> extractPointWithAICommand(command, maestroCommand)
            is ExtractComponentWithAICommand -> extractComponentWithAICommand(command, maestroCommand)
            is InputTextCommand -> inputTextCommand(command)
            is InputRandomCommand -> inputTextRandomCommand(command)
            is LaunchAppCommand -> launchAppCommand(command)
            is SetPermissionsCommand -> setPermissionsCommand(command)
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
            is SetOrientationCommand -> setOrientationCommand(command)
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
        val debugMessage = """
            Assertion '${command.condition.description()}' failed. Check the UI hierarchy in debug artifacts to verify the element state and properties.
            
            Possible causes:
            - Element selector may be incorrect - check if there are similar elements with slightly different names/properties.
            - Element may be temporarily unavailable due to loading state
            - This could be a real regression that needs to be addressed
        """.trimIndent()
        if (!evaluateCondition(command.condition, timeoutMs = timeout, commandOptional = command.optional)) {
            throw MaestroException.AssertionFailure(
                message = "Assertion is false: ${command.condition.description()}",
                hierarchyRoot = maestro.viewHierarchy().root,
                debugMessage = debugMessage
            )
        }

        return false
    }

    private suspend fun assertNoDefectsWithAICommand(
        command: AssertNoDefectsWithAICommand,
        maestroCommand: MaestroCommand
    ): Boolean {

        val metadata = getMetadata(maestroCommand)

        val imageData = Buffer()
        maestro.takeScreenshot(imageData, compressed = false)

        val defects = AIPredictionEngine.findDefects(
            screen = imageData.copy().readByteArray(),
            aiClient = ai,
        )

        if (defects.isNotEmpty()) {
            onCommandGeneratedOutput(command, defects, imageData)

            val word = if (defects.size == 1) "defect" else "defects"
            val reasoning =
                "Found ${defects.size} possible $word:\n${defects.joinToString("\n") { "- ${it.reasoning}" }}"

            updateMetadata(maestroCommand, metadata.copy(aiReasoning = reasoning))


            throw MaestroException.AssertionFailure(
                message = """
                    |$reasoning
                    |
                    """.trimMargin(),
                hierarchyRoot = maestro.viewHierarchy().root,
                debugMessage = "AI-powered visual defect detection failed. Check the UI and screenshots in debug artifacts to verify if there are actual visual issues that were missed or if the AI detection needs adjustment."
            )
        }

        return false
    }

    private suspend fun assertWithAICommand(command: AssertWithAICommand, maestroCommand: MaestroCommand): Boolean {

        val metadata = getMetadata(maestroCommand)

        val imageData = Buffer()
        maestro.takeScreenshot(imageData, compressed = false)
        val defect = AIPredictionEngine.performAssertion(
            screen = imageData.copy().readByteArray(),
            aiClient = ai,
            assertion = command.assertion,
        )

        if (defect != null) {
            onCommandGeneratedOutput(command, listOf(defect), imageData)

            val reasoning = "Assertion \"${command.assertion}\" failed:\n${defect.reasoning}"
            updateMetadata(maestroCommand, metadata.copy(aiReasoning = reasoning))

            throw MaestroException.AssertionFailure(
                message = """
                    |$reasoning
                    """.trimMargin(),
                hierarchyRoot = maestro.viewHierarchy().root,
                debugMessage = "AI-powered assertion failed. Check the UI and screenshots in debug artifacts to verify if there are actual visual issues that were missed or if the AI detection needs adjustment.")
        }

        return false
    }

    private suspend fun extractTextWithAICommand(
        command: ExtractTextWithAICommand,
        maestroCommand: MaestroCommand
    ): Boolean {

        val metadata = getMetadata(maestroCommand)

        val imageData = Buffer()
        maestro.takeScreenshot(imageData, compressed = false)
        val text = AIPredictionEngine.extractText(
            screen = imageData.copy().readByteArray(),
            aiClient = ai,
            query = command.query,
        )

        updateMetadata(
            maestroCommand, metadata.copy(
                aiReasoning = "Query: \"${command.query}\"\nExtracted text: $text"
            )
        )
        jsEngine.putEnv(command.outputVariable, text)

        return false
    }

    private suspend fun extractPointWithAICommand(
        command: ExtractPointWithAICommand,
        maestroCommand: MaestroCommand
    ): Boolean {

        val metadata = getMetadata(maestroCommand)
        val reasoningLog = StringBuilder()
        reasoningLog.appendLine("Query: \"${command.query}\"")

        // Take screenshot
        val imageData = Buffer()
        maestro.takeScreenshot(imageData, compressed = false)
        val screenshotBytes = imageData.copy().readByteArray()

        // Serialize view hierarchy for context
        val hierarchy = maestro.viewHierarchy()
        val serializedHierarchy = ViewHierarchySerializer.serialize(hierarchy.root)

        // --- PASS 1: Initial extraction with chain-of-thought + hierarchy ---
        var bestPoint = "50%,50%"
        reasoningLog.appendLine("\n--- Pass 1: Initial extraction ---")
        val pass1Response = try {
            AIPredictionEngine.extractPointWithReasoning(
                screen = screenshotBytes,
                aiClient = ai,
                query = command.query,
                viewHierarchy = serializedHierarchy,
            )
        } catch (e: Exception) {
            logger.warn("Pass 1 failed, using default point", e)
            reasoningLog.appendLine("Pass 1 FAILED: ${e.message}")
            null
        }

        if (pass1Response != null) {
            bestPoint = pass1Response.text
            reasoningLog.appendLine("Reasoning: ${pass1Response.reasoning}")
            reasoningLog.appendLine("Description: ${pass1Response.description}")
            reasoningLog.appendLine("Bounding region: ${pass1Response.boundingRegion}")
            reasoningLog.appendLine("Point: ${pass1Response.text}")
        }

        val (pass1X, pass1Y) = parsePercentagePoint(bestPoint)
        val maxPasses = command.passes.coerceIn(1, 3)

        // --- PASS 2: Crop refinement ---
        if (maxPasses >= 2) {
            reasoningLog.appendLine("\n--- Pass 2: Crop refinement ---")
            try {
                val cropResult = ImageCropUtil.cropAroundPoint(screenshotBytes, pass1X, pass1Y, cropPercent = 30)
                val contextDesc = "Cropped 30% region around Pass 1 point (${pass1X}%,${pass1Y}%). Element: ${pass1Response?.description ?: command.query}"

                val pass2Response = AIPredictionEngine.extractPointRefined(
                    croppedScreen = cropResult.croppedPng,
                    aiClient = ai,
                    query = command.query,
                    contextDescription = contextDesc,
                )

                if (pass2Response != null) {
                    val (croppedX, croppedY) = parsePercentagePoint(pass2Response.text)
                    val (mappedX, mappedY) = ImageCropUtil.mapCroppedPercentToOriginalPercent(croppedX, croppedY, cropResult)
                    bestPoint = "${mappedX}%,${mappedY}%"
                    reasoningLog.appendLine("Reasoning: ${pass2Response.reasoning}")
                    reasoningLog.appendLine("Cropped point: ${pass2Response.text} -> Mapped to full: $bestPoint")
                }
            } catch (e: Exception) {
                logger.warn("Pass 2 failed, using Pass 1 result", e)
                reasoningLog.appendLine("Pass 2 FAILED: ${e.message}")
            }
        }

        val (refinedX, refinedY) = parsePercentagePoint(bestPoint)

        // --- PASS 3: Validation ---
        if (maxPasses >= 3) {
            reasoningLog.appendLine("\n--- Pass 3: Validation ---")
            try {
                val pass3Response = AIPredictionEngine.validatePoint(
                    screen = screenshotBytes,
                    aiClient = ai,
                    query = command.query,
                    pointXPercent = refinedX,
                    pointYPercent = refinedY,
                )

                if (pass3Response != null) {
                    reasoningLog.appendLine("Is correct: ${pass3Response.isCorrect}")
                    reasoningLog.appendLine("Reasoning: ${pass3Response.reasoning}")
                    if (!pass3Response.isCorrect) {
                        bestPoint = pass3Response.correctedText
                        reasoningLog.appendLine("Corrected point: ${pass3Response.correctedText}")
                    }
                }
            } catch (e: Exception) {
                logger.warn("Pass 3 failed, using Pass 2 result", e)
                reasoningLog.appendLine("Pass 3 FAILED: ${e.message}")
            }
        }

        reasoningLog.appendLine("\nFinal point: $bestPoint")

        updateMetadata(
            maestroCommand, metadata.copy(
                aiReasoning = reasoningLog.toString()
            )
        )
        jsEngine.putEnv(command.outputVariable, bestPoint)

        return false
    }

    private fun parsePercentagePoint(text: String): Pair<Int, Int> {
        return try {
            val parts = text.replace("%", "").split(",").map { it.trim().toInt() }
            Pair(parts[0].coerceIn(0, 100), parts[1].coerceIn(0, 100))
        } catch (e: Exception) {
            logger.warn("Failed to parse percentage point from '$text', defaulting to 50,50", e)
            Pair(50, 50)
        }
    }

    private suspend fun extractComponentWithAICommand(
        command: ExtractComponentWithAICommand,
        maestroCommand: MaestroCommand
    ): Boolean {

        val metadata = getMetadata(maestroCommand)
        val reasoningLog = StringBuilder()
        reasoningLog.appendLine("Image: \"${command.imagePath}\"")

        // Resolve image path: if relative and APP_ROOT is set, resolve against it
        val imageFile = File(command.imagePath).let { file ->
            if (file.isAbsolute) file
            else {
                val appRoot = System.getenv("APP_ROOT")
                if (appRoot != null) File(appRoot, command.imagePath)
                else file
            }
        }
        val componentImageBytes = imageFile.readBytes()

        // Take screenshot
        val imageData = Buffer()
        maestro.takeScreenshot(imageData, compressed = false)
        val screenshotBytes = imageData.copy().readByteArray()

        // Serialize view hierarchy for context
        val hierarchy = maestro.viewHierarchy()
        val serializedHierarchy = ViewHierarchySerializer.serialize(hierarchy.root)

        // --- PASS 1: Initial extraction with component image + screenshot + hierarchy ---
        var bestPoint = "50%,50%"
        reasoningLog.appendLine("\n--- Pass 1: Initial extraction ---")
        val pass1Response = try {
            AIPredictionEngine.extractComponentPoint(
                componentImage = componentImageBytes,
                screen = screenshotBytes,
                aiClient = ai,
                viewHierarchy = serializedHierarchy,
            )
        } catch (e: Exception) {
            logger.warn("Pass 1 failed, using default point", e)
            reasoningLog.appendLine("Pass 1 FAILED: ${e.message}")
            null
        }

        if (pass1Response != null) {
            bestPoint = pass1Response.text
            reasoningLog.appendLine("Reasoning: ${pass1Response.reasoning}")
            reasoningLog.appendLine("Description: ${pass1Response.description}")
            reasoningLog.appendLine("Bounding region: ${pass1Response.boundingRegion}")
            reasoningLog.appendLine("Point: ${pass1Response.text}")
        }

        reasoningLog.appendLine("\nFinal point: $bestPoint")

        updateMetadata(
            maestroCommand, metadata.copy(
                aiReasoning = reasoningLog.toString()
            )
        )
        jsEngine.putEnv(command.outputVariable, bestPoint)

        return false
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

    private fun setOrientationCommand(command: SetOrientationCommand): Boolean {
        maestro.setOrientation(command.orientation)

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
        val maxRetryCenterCount = 4 // for when the list is no longer scrollable (last element) but the element is visible

        do {
            try {
                val element = findElement(command.selector, command.optional, 500).element
                val visibility = element.getVisiblePercentage(deviceInfo.widthGrid, deviceInfo.heightGrid)

                logger.info("Scrolling try count: $retryCenterCount, DeviceWidth: ${deviceInfo.widthGrid}, DeviceWidth: ${deviceInfo.heightGrid}")
                logger.info("Element bounds: ${element.bounds}")
                logger.info("Visibility Percent: $visibility")
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
                logger.warn("Error: $ignored")
            }
            maestro.swipeFromCenter(
                direction,
                durationMs = command.scrollDuration.toLong(),
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
            )
        } while (System.currentTimeMillis() < endTime)

        val debugMessage = buildString {
            appendLine("Could not find a visible element matching selector: ${command.selector.description()}")
            appendLine("Tip: Try adjusting the following settings to improve detection:")
            appendLine("- `timeout`: current = ${command.timeout}ms → Increase if you need more time to find the element")
            val originalSpeed = command.originalSpeedValue?.toIntOrNull()
            val speedAdvice = if (originalSpeed != null && originalSpeed > 50) {
                "Reduce for slower, more precise scrolling to avoid overshooting elements"
            } else {
                "Increase for faster scrolling if element is far away"
            }
            appendLine("- `speed`: current = ${command.originalSpeedValue} (0-100 scale) → $speedAdvice")
            val waitSettleAdvice = if (command.waitToSettleTimeoutMs == null) {
                "Set this value (e.g., 500ms) if your UI updates frequently between scrolls"
            } else {
                "Increase if your UI needs more time to update between scrolls"
            }
            val waitToTimeSettleMessage = if (command.waitToSettleTimeoutMs != null) {
                "${command.waitToSettleTimeoutMs}ms"
            } else {
                "Not defined"
            }
            appendLine("- `waitToSettleTimeoutMs`: current = $waitToTimeSettleMessage → $waitSettleAdvice")
            appendLine("- `visibilityPercentage`: current = ${command.visibilityPercentage}% → Lower this value if you want to detect partially visible elements")
            val centerAdvice = if (command.centerElement) {
                "Disable if you don't need the element to be centered after finding it"
            } else {
                "Enable if you want the element to be centered after finding it"
            }
            appendLine("- `centerElement`: current = ${command.centerElement} → $centerAdvice")
        }
        throw MaestroException.ElementNotFound(
            message = "No visible element found: ${command.selector.description()}",
            maestro.viewHierarchy().root,
            debugMessage = debugMessage
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

    private suspend fun repeatCommand(command: RepeatCommand, maestroCommand: MaestroCommand, config: MaestroConfig?): Boolean {
        val maxRuns = command.times?.toDoubleOrNull()?.toInt() ?: Int.MAX_VALUE

        var counter = 0
        var metadata = getMetadata(maestroCommand)
        metadata = metadata.copy(
            numberOfRuns = 0,
        )

        var mutating = false

        val checkCondition: () -> Boolean = {
            command.condition
                ?.evaluateScripts(jsEngine)
                ?.let { evaluateCondition(it, commandOptional = command.optional) } != false
        }

        while (checkCondition() && counter < maxRuns && currentCoroutineContext().isActive) {
            if (counter > 0) {
                command.commands.forEach { resetCommand(it) }
            }

            val mutated = runSubFlow(command.commands, config, null)
            mutating = mutating || mutated
            counter++

            metadata = metadata.copy(
                numberOfRuns = counter,
            )
            updateMetadata(maestroCommand, metadata)
        }

        if (counter == 0) {
            throw CommandSkipped
        }

        return mutating
    }

    private suspend fun retryCommand(command: RetryCommand, config: MaestroConfig?): Boolean {
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

    private suspend fun runFlowCommand(command: RunFlowCommand, config: MaestroConfig?): Boolean {
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

        condition.visible?.let {
            try {
                findElement(
                    selector = it,
                    timeoutMs = adjustedToLatestInteraction(timeoutMs ?: optionalLookupTimeoutMs),
                    optional = commandOptional,
                )
            } catch (_: MaestroException.ElementNotFound) {
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

        return true
    }

    private suspend fun executeSubflowCommands(commands: List<MaestroCommand>, config: MaestroConfig?): Boolean {
        jsEngine.enterScope()

        return try {
            commands
                .mapIndexed { index, command ->
                    val evaluatedCommand = command.evaluateScripts(jsEngine)
                    val metadata = getMetadata(command)
                        .copy(
                            evaluatedCommand = evaluatedCommand,
                        )
                    updateMetadata(command, metadata)

                    onCommandStart(index, command)

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
                        logger.info("[Command execution subflow] CommandWarned: ${ignored.message}")
                        insights.report(Insight(message = ignored.message, level = Insight.Level.WARNING))
                        onCommandWarned(index, command)
                        false
                    } catch (ignored: CommandSkipped) {
                        // Swallow exception
                        logger.info("[Command execution subflow] CommandSkipped: ${ignored.message}")
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

    private suspend fun runSubFlow(
        commands: List<MaestroCommand>,
        config: MaestroConfig?,
        subflowConfig: MaestroConfig?,
    ): Boolean {
        // Enter environment scope to isolate environment variables for this subflow
        jsEngine.enterEnvScope()
        return try {
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
            onCompleteSuccess && flowSuccess
        } finally {
            jsEngine.leaveEnvScope()
        }
    }

    private fun takeScreenshotCommand(command: TakeScreenshotCommand): Boolean {
        val pathStr = command.path + ".png"
        val fileSink = getFileSink(screenshotsDir, pathStr)
        maestro.takeScreenshot(fileSink, false)
        return false
    }

    private fun startRecordingCommand(command: StartRecordingCommand): Boolean {
        val pathStr = command.path + ".mp4"
        val fileSink = getFileSink(screenshotsDir, pathStr)
        screenRecording = maestro.startScreenRecording(fileSink)
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
        } catch (e: Exception) {
            logger.error("Failed to clear state", e)
            throw MaestroException.UnableToClearState("Unable to clear state for app ${command.appId}: ${e.message}", e)
        }

        try {
            // For testing convenience, default to allow all on app launch
            val permissions = command.permissions ?: mapOf("all" to "allow")
            maestro.setPermissions(command.appId, permissions)
        } catch (e: Exception) {
            logger.error("Failed to set permissions", e)
            throw MaestroException.UnableToSetPermissions("Unable to set permissions for app ${command.appId}: ${e.message}", e)
        }

        try {
            maestro.launchApp(
                appId = command.appId,
                launchArguments = command.launchArguments ?: emptyMap(),
                stopIfRunning = command.stopApp ?: true
            )
        } catch (e: Exception) {
            logger.error("Failed to launch app", e)
            throw MaestroException.UnableToLaunchApp("Unable to launch app ${command.appId}", cause = e)
        }

        return true
    }

    private fun setPermissionsCommand(command: SetPermissionsCommand): Boolean {
        try {
            maestro.setPermissions(command.appId, command.permissions)
        } catch (e: Exception) {
            throw MaestroException.UnableToSetPermissions("Unable to set permissions for app ${command.appId}: ${e.message}", e)
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

    private fun assertVisualCommand(command: AssertVisualCommand): Boolean {
        val baseline = command.baseline + ".png"

        val actual = screenshotsDir
            ?.let { File(it.toFile(), baseline) }
            ?: File(baseline)

        val expected = File
            .createTempFile("screenshot-${System.currentTimeMillis()}", ".png")
            .also { it.deleteOnExit() }

        maestro.takeScreenshot(expected.sink(), false)

        if (!actual.exists()) {
            expected.copyTo(actual, overwrite = true)
            return true
        }

        val photoNow: BufferedImage = ImageIO.read(expected)
        val oldPhoto: BufferedImage = ImageIO.read(actual)
        val regressionFailedFile = File
            .createTempFile("failed_visual_regression-${System.currentTimeMillis()}", ".png")
            .also { it.deleteOnExit() }
        val comparison =
            ImageComparison(photoNow, oldPhoto, regressionFailedFile)

        val thresholdDifferencePercentage = (100 - command.thresholdPercentage).toDouble()
        comparison.apply {
            allowingPercentOfDifferentPixels = thresholdDifferencePercentage
            rectangleLineWidth = 10
            pixelToleranceLevel = 50.00
            minimalRectangleSize = 40
        }

        val comparisonState = comparison.compareImages()

        if (ImageComparisonState.MISMATCH === comparisonState.imageComparisonState) {
            ImageConcatenate.concatenate(
                arrayOf(
                    ImageIO.read(expected),
                    ImageIO.read(regressionFailedFile),
                    ImageIO.read(actual),
                ), "failed_visual_regression")

            val debugMessage = """
                Comparison '${command.description()}' failed.
            """.trimIndent()

            throw MaestroException.AssertionFailure(
                message = "Comparison error: ${command.description()} - threshold not met, current: ${100 - comparisonState.differencePercent}",
                hierarchyRoot = maestro.viewHierarchy().root,
                debugMessage = debugMessage
            )
        }
        return false
    }

    private fun tapOnElement(
        command: TapOnElementCommand,
        retryIfNoChange: Boolean,
        waitUntilVisible: Boolean,
        config: MaestroConfig?,
    ): Boolean {
        val result = findElement(command.selector, optional = command.optional)


        // Handle element-relative tap if specified
        val relativePoint = command.relativePoint
        if (relativePoint != null) {
            val tapPoint = calculateElementRelativePoint(result.element, relativePoint)

            maestro.tap(
                x = tapPoint.x,
                y = tapPoint.y,
                retryIfNoChange = retryIfNoChange,
                longPress = command.longPress ?: false,
                tapRepeat = command.repeat,
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs,
            )
        } else {
            // Default behavior: tap at element center
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
        }

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
                retryIfNoChange = command.retryIfNoChange ?: false,
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
                retryIfNoChange = command.retryIfNoChange ?: false,
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
        val debugMessage = """
            Element with $description not found. Check the UI hierarchy in debug artifacts to verify if the element exists.
            
            Possible causes:
            - Element selector may be incorrect - check if there are similar elements with slightly different names/properties.
            - Element may be temporarily unavailable due to loading state.
            - This could be a real regression that needs to be addressed.
        """.trimIndent()
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
                debugMessage = debugMessage
            )
        }


        val exceptionDebugMessage = """
            Element with $description not found. Check the UI hierarchy in debug artifacts to verify if the element exists.
            
            Possible causes:
            - Element selector may be incorrect - check if there are similar elements with slightly different names/properties.
            - Element may be temporarily unavailable due to loading state.
            - This could be a real regression that needs to be addressed.
        """.trimIndent()
        return maestro.findElementWithTimeout(
            timeoutMs = timeout,
            filter = filterFunc
        ) ?: throw MaestroException.ElementNotFound(
            "Element not found: $description",
            maestro.viewHierarchy().root,
            debugMessage = exceptionDebugMessage
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
        val debugMessage = """
            Element with $description not found. Check the UI hierarchy in debug artifacts to verify if the element exists.
            
            Possible causes:
            - Element selector may be incorrect - check if there are similar elements with slightly different names/properties.
            - Element may be temporarily unavailable due to loading state.
            - This could be a real regression that needs to be addressed.
        """.trimIndent()
        return maestro.findElementWithTimeout(
            timeout,
            filterFunc,
            parentViewHierarchy
        )?.hierarchy ?: throw MaestroException.ElementNotFound(
            "Element not found: $description",
            parentViewHierarchy.root,
            debugMessage = debugMessage
        )
    }

    private fun buildFilter(
        selector: ElementSelector,
    ): FilterWithDescription {
        val basicFilters = mutableListOf<ElementFilter>()
        val relativeFilters = mutableListOf<ElementFilter>()
        val descriptions = mutableListOf<String>()

        selector.textRegex
            ?.let {
                descriptions += "Text matching regex: $it"
                basicFilters += Filters.textMatches(it.toRegexSafe(REGEX_OPTIONS))
            }

        selector.idRegex
            ?.let {
                descriptions += "Id matching regex: $it"
                basicFilters += Filters.idMatches(it.toRegexSafe(REGEX_OPTIONS))
            }
        selector.size
            ?.let {
                descriptions += "Size: $it"
                basicFilters += Filters.sizeMatches(
                    width = it.width,
                    height = it.height,
                    tolerance = it.tolerance,
                ).asFilter()
            }

        selector.below
            ?.let {
                descriptions += "Below: ${it.description()}"
                relativeFilters += Filters.below(buildFilter(it).filterFunc)
            }

        selector.above
            ?.let {
                descriptions += "Above: ${it.description()}"
                relativeFilters += Filters.above(buildFilter(it).filterFunc)
            }

        selector.leftOf
            ?.let {
                descriptions += "Left of: ${it.description()}"
                relativeFilters += Filters.leftOf(buildFilter(it).filterFunc)
            }

        selector.rightOf
            ?.let {
                descriptions += "Right of: ${it.description()}"
                relativeFilters += Filters.rightOf(buildFilter(it).filterFunc)
            }

        selector.containsChild
            ?.let {
                descriptions += "Contains child: ${it.description()}"
                relativeFilters += Filters.containsChild(findElement(it, optional = false).element).asFilter()
            }

        selector.containsDescendants
            ?.let { descendantSelectors ->
                val descendantDescriptions = descendantSelectors.joinToString("; ") { it.description() }
                descriptions += "Contains descendants: $descendantDescriptions"
                relativeFilters += Filters.containsDescendants(descendantSelectors.map { buildFilter(it).filterFunc })
            }

        selector.traits
            ?.map {
                TraitFilters.buildFilter(it)
            }
            ?.forEach { (description, filter) ->
                descriptions += description
                basicFilters += filter
            }

        selector.enabled
            ?.let {
                descriptions += if (it) {
                    "Enabled"
                } else {
                    "Disabled"
                }
                basicFilters += Filters.enabled(it)
            }

        selector.selected
            ?.let {
                descriptions += if (it) {
                    "Selected"
                } else {
                    "Not selected"
                }
                basicFilters += Filters.selected(it)
            }

        selector.checked
            ?.let {
                descriptions += if (it) {
                    "Checked"
                } else {
                    "Not checked"
                }
                basicFilters += Filters.checked(it)
            }

        selector.focused
            ?.let {
                descriptions += if (it) {
                    "Focused"
                } else {
                    "Not focused"
                }
                basicFilters += Filters.focused(it)
            }

        selector.css
            ?.let {
                descriptions += "CSS: $it"
                basicFilters += Filters.css(maestro, it)
            }

        // Apply deepestMatchingElement only to basic filters, then intersect with relative filters
        val basicFilter = if (basicFilters.isNotEmpty()) {
            Filters.deepestMatchingElement(Filters.intersect(basicFilters))
        } else {
            { nodes -> nodes } // Identity filter if no basic filters
        }

        val allFilters = listOf(basicFilter) + relativeFilters
        var resultFilter = Filters.intersect(allFilters)

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
        val start = command.start
        val end = command.end
        when {
            elementSelector != null && direction != null -> {
                val uiElement = findElement(elementSelector, optional = command.optional)
                maestro.swipe(
                    direction,
                    uiElement.element,
                    command.duration,
                    waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
                )
            }

            startRelative != null && endRelative != null -> {
                maestro.swipe(
                    startRelative = startRelative,
                    endRelative = endRelative,
                    duration = command.duration,
                    waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
                )
            }

            direction != null -> maestro.swipe(
                swipeDirection = direction,
                duration = command.duration,
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
            )

            start != null && end != null -> {
                if(start.contains("%") || end.contains("%")){
                    maestro.swipe(
                        startRelative = start,
                        endRelative = end,
                        duration = command.duration,
                        waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
                    )
                } else {
                    maestro.swipe(
                        start = start,
                        end = end,
                        duration = command.duration,
                        waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
                    )
                }
            }

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

    private fun setClipboardCommand(command: SetClipboardCommand): Boolean {
        copiedText = command.text
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

    private fun getFileSink(parentPath: Path?, filePathStr: String): BufferedSink {
        // Work out relative v absolute input
        val resolvedFile = parentPath?.resolve(filePathStr)?.toFile() ?: File(filePathStr)
        val absoluteFile = resolvedFile.absoluteFile

        if(absoluteFile.parentFile.exists() || absoluteFile.parentFile.mkdirs()) {
            return resolvedFile
                .sink()
                .buffer()
        } else {
            throw MaestroException.DestinationIsNotWritable(
                "Unable to create directory for file: ${absoluteFile.parentFile.absolutePath}"
            )
        }
    }

    private suspend fun executeDefineVariablesCommands(commands: List<MaestroCommand>, config: MaestroConfig?) {
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
        val aiReasoning: String? = null,
        val labeledCommand: String? = null
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

    // Remove pause/resume functions that were storing/restoring engine
    fun pause() {
        flowController.pause()
    }

    fun resume() {
        flowController.resume()
    }

    val isPaused: Boolean
        get() = flowController.isPaused
}