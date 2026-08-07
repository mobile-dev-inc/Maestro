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

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.yield
import maestro.Driver
import maestro.ElementFilter
import maestro.Filters
import io.grpc.Status
import maestro.*
import maestro.Filters.asFilter
import maestro.FindElementResult
import maestro.Maestro
import maestro.DeviceConnectionException
import maestro.MaestroException
import maestro.Point
import maestro.ScreenRecording
import maestro.UiElement
import maestro.UiElement.Companion.toUiElementOrNull
import maestro.ViewHierarchy
import maestro.ai.cloud.Defect
import maestro.ai.CloudAIPredictionEngine
import maestro.ai.AIPredictionEngine
import maestro.js.GraalJsEngine
import maestro.js.JsEngine
import maestro.orchestra.ArtifactKind
import maestro.orchestra.ArtifactManifest
import maestro.orchestra.backend.BackendContext
import maestro.orchestra.backend.ExecutionBackend
import maestro.orchestra.backend.LegacyExecutionBackend
import maestro.orchestra.debug.ArtifactsGenerator
import maestro.orchestra.debug.BundleLayout
import maestro.orchestra.debug.ArtifactCollector
import maestro.orchestra.debug.CommandOutcome
import maestro.orchestra.debug.FlowDebugOutput
import maestro.orchestra.debug.OrchestraListener
import maestro.orchestra.filter.FilterWithDescription
import maestro.orchestra.filter.TraitFilters
import maestro.orchestra.util.Env.evaluateScripts
import maestro.orchestra.yaml.YamlCommandReader
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
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.lang.Long.max
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.Paths
import java.util.logging.Filter
import kotlin.coroutines.coroutineContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

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
    private val artifactsDir: Path? = null,
    private val captureFullArtifacts: Boolean = false,
    private val listeners: List<OrchestraListener> = emptyList(),
    private val lookupTimeoutMs: Long = 17000L,
    private val optionalLookupTimeoutMs: Long = 7000L,
    // The execution seam. Orchestra routes device-touching commands here as they are relocated.
    // Defaults to the legacy backend built over the same maestro/timeouts, so behavior is unchanged.
    private val backend: ExecutionBackend = LegacyExecutionBackend(maestro, lookupTimeoutMs, optionalLookupTimeoutMs),
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
    private val onStepScreenshotCaptured: (sequenceNumber: Int, relativePath: String) -> Unit = { _, _ -> },
    private val onCommandGeneratedOutput: (command: Command, defects: List<Defect>, screenshot: Buffer) -> Unit = { _, _, _ -> },
    private val apiKey: String? = null,
    private val AIPredictionEngine: AIPredictionEngine? = apiKey?.let { CloudAIPredictionEngine(it) },
    private val flowController: FlowController = DefaultFlowController(),
    internal val jsEngineFactory: (MaestroConfig?) -> JsEngine = { config ->
        // Defense-in-depth: WorkspaceValidator is the primary gate for `jsEngine: rhino`,
        // but throw here too in case Orchestra is invoked outside the validation pipeline.
        check(config?.ext?.get("jsEngine") != "rhino") {
            "The Rhino JS engine has been removed. Remove `jsEngine: rhino` from your config; " +
                "flows now run on GraalJS, the default engine."
        }
        val platform = maestro.cachedDeviceInfo.platform.toString().lowercase()
        httpClient?.let { GraalJsEngine(it, platform) } ?: GraalJsEngine(platform = platform)
    },
) {

    private lateinit var jsEngine: JsEngine

    private var copiedText: String? = null

    private var timeMsOfLastInteraction = System.currentTimeMillis()

    private var screenRecording: ScreenRecording? = null

    private val rawCommandToMetadata = mutableMapOf<MaestroCommand, CommandMetadata>()

    // ArtifactsGenerator is always the first listener: it writes the bundle when
    // artifactsDir is set and populates debugOutput either way.
    private val artifactsGenerator: ArtifactsGenerator =
        ArtifactsGenerator(artifactsDir, maestro, captureFullArtifacts, onStepScreenshotCaptured)
    private val effectiveListeners: List<OrchestraListener> = listOf(artifactsGenerator) + listeners

    private var commandSequenceCounter: Int = 0

    // Dispatched to listeners as `depth`: 0 at the flow top, bumped inside each subflow.
    private var subflowDepth: Int = 0

    // Keyed by sequence number, not MaestroCommand: the latter has structural
    // equality, so two identical commands would collide as map keys.
    private val commandStartTimes = mutableMapOf<Int, Long>()

    data class FlowResult(
        val success: Boolean,
        val debugOutput: FlowDebugOutput,
        val artifactManifest: ArtifactManifest,
    )

    suspend fun runFlow(commands: List<MaestroCommand>): FlowResult {
        timeMsOfLastInteraction = System.currentTimeMillis()

        val config = YamlCommandReader.getConfig(commands)

        initJsEngine(config)
        initAndroidChromeDevTools(config)

        onFlowStart(commands)
        dispatch("onFlowStart") { it.onFlowStart() }

        var flowSuccess = false
        var exception: Throwable? = null
        try {
            executeDefineVariablesCommands(commands, config)
            // filter out DefineVariablesCommand to not execute it twice
            val filteredCommands = commands.filter { it.asCommand() !is DefineVariablesCommand }

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            exception = e
        } finally {
            val onCompleteSuccess = if (currentCoroutineContext().isActive) {
                config?.onFlowComplete?.commands?.let {
                    try {
                        executeCommands(
                            commands = it,
                            config = config,
                            shouldReinitJsEngine = false,
                        )
                    } catch (e: CancellationException) {
                        // The whole run is being killed (timeout/cancel), not a hook failure — propagate.
                        throw e
                    } catch (e: Throwable) {
                        // Must not escape this `finally`: that skips onFlowEnd, where device logs are
                        // collected and manifest.json written, so a failed run would ship no logs.
                        // Still rethrown below (Case 109), unless the flow body already failed — that wins.
                        logger.warn("onFlowComplete hook failed: ${e.message}")
                        if (exception == null) exception = e
                        false
                    }
                } ?: true
            } else {
                true
            }

            jsEngine.close()

            dispatch("onFlowEnd") { it.onFlowEnd() }

            exception?.let { throw it }

            return FlowResult(
                success = onCompleteSuccess && flowSuccess,
                debugOutput = artifactsGenerator.debugOutput,
                artifactManifest = artifactsGenerator.artifactManifest,
            )
        }
    }

    private suspend fun executeCommands(
        commands: List<MaestroCommand>,
        config: MaestroConfig? = null,
        shouldReinitJsEngine: Boolean = true,
    ): Boolean {
        if (shouldReinitJsEngine) {
            initJsEngine(config)
        }

        yield()
        initAndroidChromeDevTools(config)

        commands
            .forEachIndexed { index, command ->
                yield()

                // Check for pause before executing each command
                flowController.waitIfPaused()

                onCommandStart(index, command)
                val sequenceNumber = commandSequenceCounter++
                val startedAt = System.currentTimeMillis()
                commandStartTimes[sequenceNumber] = startedAt
                dispatch("onCommandStart") { it.onCommandStart(command, sequenceNumber, subflowDepth) }

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
                        dispatchFinished(command, CommandOutcome.Completed, sequenceNumber)
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
                    dispatchFinished(command, CommandOutcome.Warned, sequenceNumber)
                    onCommandWarned(index, command)
                } catch (ignored: CommandSkipped) {
                    logger.info("[Command execution] CommandSkipped: ${ignored.message}")
                    // Swallow exception
                    dispatchFinished(command, CommandOutcome.Skipped, sequenceNumber)
                    onCommandSkipped(index, command)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DeviceConnectionException) {
                    throw e
                } catch (e: Throwable) {
                    logger.error("[Command execution] CommandFailed: ${e.message}")
                    dispatchFinished(command, CommandOutcome.Failed(e), sequenceNumber)
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
        jsEngine = jsEngineFactory(config)
    }

    private suspend fun initAndroidChromeDevTools(config: MaestroConfig?) {
        if (config == null) return
        val shouldEnableAndroidChromeDevTools = config.ext["androidWebViewHierarchy"] == "devtools"
        maestro.setAndroidChromeDevToolsEnabled(shouldEnableAndroidChromeDevTools)
    }

    /**
     * Returns true if the command mutated device state (i.e. interacted with the device), false otherwise.
     */
    private suspend fun executeCommand(maestroCommand: MaestroCommand, config: MaestroConfig?): Boolean {
        val command = maestroCommand.asCommand()

        flowController.waitIfPaused()

        return when (command) {
            is TapOnElementCommand,
            is LaunchAppCommand,
            is StopAppCommand,
            is KillAppCommand,
            is ClearStateCommand,
            is ClearKeychainCommand,
            is OpenLinkCommand,
            is PressKeyCommand,
            is EraseTextCommand,
            is BackPressCommand,
            is HideKeyboardCommand,
            is AssertConditionCommand,
            is AssertCommand,
            is TapOnPointCommand,
            is TapOnPointV2Command,
            is ScrollCommand,
            is SetPermissionsCommand,
            is WaitForAnimationToEndCommand,
            is SetLocationCommand,
            is SetOrientationCommand,
            is AddMediaCommand,
            is SetAirplaneModeCommand,
            is ToggleAirplaneModeCommand,
            is SetDarkModeCommand,
            is ToggleDarkModeCommand,
            is AssertDarkModeCommand,
            is AssertLightModeCommand,
            is TravelCommand,
            is ScrollUntilVisibleCommand,
            is InputTextCommand,
            is InputRandomCommand,
            is PasteTextCommand,
            is SwipeCommand -> {
                backend.execute(
                    command,
                    BackendContext(
                        lookupTimeoutMs = lookupTimeoutMs,
                        optionalLookupTimeoutMs = optionalLookupTimeoutMs,
                        timeMsOfLastInteraction = timeMsOfLastInteraction,
                        appId = config?.appId,
                        copiedText = copiedText,
                    ),
                ).mutating
            }

            // Dedicated branch: the backend resolves + extracts the text and returns it via
            // result.output; the router owns copiedText and the JS engine above the seam.
            is CopyTextFromCommand -> {
                val r = backend.execute(
                    command,
                    BackendContext(
                        lookupTimeoutMs = lookupTimeoutMs,
                        optionalLookupTimeoutMs = optionalLookupTimeoutMs,
                        timeMsOfLastInteraction = timeMsOfLastInteraction,
                        appId = config?.appId,
                        copiedText = copiedText,
                    ),
                )
                copiedText = r.output
                jsEngine.setCopiedText(copiedText)
                r.mutating
            }
            is SetClipboardCommand -> setClipboardCommand(command)
            is AssertScreenshotCommand -> assertScreenshotCommand(command)
            is AssertNoDefectsWithAICommand -> assertNoDefectsWithAICommand(command, maestroCommand)
            is AssertWithAICommand -> assertWithAICommand(command, maestroCommand)
            is ExtractTextWithAICommand -> extractTextWithAICommand(command, maestroCommand)
            is TakeScreenshotCommand -> takeScreenshotCommand(command)
            is RunFlowCommand -> runFlowCommand(command, config)
            is RepeatCommand -> repeatCommand(command, maestroCommand, config)
            is DefineVariablesCommand -> defineVariablesCommand(command)
            is RunScriptCommand -> runScriptCommand(command)
            is EvalScriptCommand -> evalScriptCommand(command)
            is ApplyConfigurationCommand -> false
            is StartRecordingCommand -> startRecordingCommand(command)
            is StopRecordingCommand -> stopRecordingCommand()
            is RetryCommand -> retryCommand(command, config)
            else -> true
        }.also { mutating ->
            if (mutating) {
                timeMsOfLastInteraction = System.currentTimeMillis()
            }
        }
    }

    // Dead code as of Task 1.6: AssertLightModeCommand now routes to LegacyExecutionBackend's own
    // assertDarkMode(expected: Boolean), and AssertDarkModeCommand already did. Nothing in
    // Orchestra calls this anymore. Left in place per the task brief — the later consolidation
    // pass removes now-unreferenced leftovers like this one.
    private suspend fun assertDarkMode(expected: Boolean): Boolean {
        val actual = maestro.isDarkModeEnabled()
        if (actual != expected) {
            val expectedState = if (expected) "enabled" else "disabled"
            val actualState = if (actual) "dark mode" else "light mode"
            throw MaestroException.AssertionFailure(
                message = "Assertion failed: expected dark mode to be $expectedState, but it was ${if (actual) "enabled" else "disabled"}",
                hierarchyRoot = maestro.viewHierarchy().root,
                debugMessage = "The device's system-wide appearance is currently $actualState. Use setDarkMode or toggleDarkMode to change it before this assertion."
            )
        }

        return false
    }

    private suspend fun assertNoDefectsWithAICommand(
        command: AssertNoDefectsWithAICommand,
        maestroCommand: MaestroCommand
    ): Boolean {
        if (AIPredictionEngine == null) {
            throw MaestroException.CloudApiKeyNotAvailable("`MAESTRO_CLOUD_API_KEY` is not available. Did you export MAESTRO_CLOUD_API_KEY?")
        }

        val metadata = getMetadata(maestroCommand)

        val imageData = Buffer()
        maestro.takeScreenshot(imageData, compressed = false)

        val defects = AIPredictionEngine.findDefects(
            screen = imageData.copy().readByteArray(),
        )

        if (defects.isNotEmpty()) {
            dispatch("onAIArtifactGenerated") { it.onAIArtifactGenerated(imageData.copy(), defects.size) }
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
        if (AIPredictionEngine == null) {
            throw MaestroException.CloudApiKeyNotAvailable("`MAESTRO_CLOUD_API_KEY` is not available. Did you export MAESTRO_CLOUD_API_KEY?")
        }

        val metadata = getMetadata(maestroCommand)

        val imageData = Buffer()
        maestro.takeScreenshot(imageData, compressed = false)
        val defect = AIPredictionEngine.performAssertion(
            screen = imageData.copy().readByteArray(),
            assertion = command.assertion,
        )

        if (defect != null) {
            dispatch("onAIArtifactGenerated") { it.onAIArtifactGenerated(imageData.copy(), 1) }
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
        if (AIPredictionEngine == null) {
            throw MaestroException.CloudApiKeyNotAvailable("`MAESTRO_CLOUD_API_KEY` is not available. Did you export MAESTRO_CLOUD_API_KEY?")
        }

        val metadata = getMetadata(maestroCommand)

        val imageData = Buffer()
        maestro.takeScreenshot(imageData, compressed = false)
        val text = AIPredictionEngine.extractText(
            screen = imageData.copy().readByteArray(),
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

    private fun normalizeScreenshotPath(path: String): String {
        val imageExtensions = listOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".tiff", ".wbmp", ".heic", ".heif")
        return if (imageExtensions.any { path.endsWith(it, ignoreCase = true) }) path else "$path.png"
    }

    private suspend fun assertScreenshotCommand(command: AssertScreenshotCommand): Boolean {
        val thresholdPercentage = command.thresholdPercentage.toDoubleOrNull()
            ?: throw MaestroException.AssertionFailure(
                message = "Invalid thresholdPercentage for assertScreenshot: \"${command.thresholdPercentage}\". Expected a number.",
                hierarchyRoot = maestro.viewHierarchy().root,
                debugMessage = "The assertScreenshot thresholdPercentage must resolve to a number (e.g. 95). " +
                    "If you are using a variable, make sure it evaluates to a numeric value."
            )

        val path = normalizeScreenshotPath(command.path)

        val candidates = buildList {
            command.flowPath?.let { add(it.resolve(path).toFile()) }
            artifactsDir?.let { add(it.resolve(BundleLayout.TAKE_SCREENSHOT_DIR).resolve(path).normalize().toFile()) }
            add(File(path))
        }.distinctBy { it.canonicalPath }

        val expectedFile = candidates.firstOrNull { it.exists() }
            ?: throw MaestroException.AssertionFailure(
                message = "Screenshot file not found: $path. Searched in:\n" +
                    candidates.joinToString("\n") { "  - ${it.absolutePath}" },
                hierarchyRoot = maestro.viewHierarchy().root,
                debugMessage = "The assertScreenshot command requires a pre-existing reference screenshot. " +
                    "Create it at one of the searched locations above."
            )

        expectedFile.parentFile?.mkdirs()

        // Temp file is always PNG since maestro.takeScreenshot produces PNG
        val actualScreenshotFile = File
            .createTempFile("screenshot-${System.currentTimeMillis()}", ".png")
            .also { it.deleteOnExit() }

        val cropOn = command.cropOn
        if (cropOn != null) {
            val elementResult = findElement(cropOn, optional = command.optional)
            val bounds = elementResult.element.bounds
            if (bounds.width <= 0 || bounds.height <= 0) {
                throw MaestroException.AssertionFailure(
                    message = "Cannot crop screenshot: element '${cropOn.description()}' has invalid dimensions (width: ${bounds.width}, height: ${bounds.height}). The element must have positive width and height to crop the screenshot.",
                    hierarchyRoot = maestro.viewHierarchy().root,
                    debugMessage = "The assertScreenshot command with cropOn requires an element with positive dimensions. The found element has bounds: x=${bounds.x}, y=${bounds.y}, width=${bounds.width}, height=${bounds.height}."
                )
            }
            maestro.takeScreenshot(actualScreenshotFile.sink(), false, bounds)
        } else {
            maestro.takeScreenshot(actualScreenshotFile.sink(), false)
        }

        val actualImage: BufferedImage = ImageIO.read(actualScreenshotFile)

        val expectedImage: BufferedImage = ImageIO.read(expectedFile) ?: throw MaestroException.AssertionFailure(
            message = "Failed to read image file: ${expectedFile.absolutePath}. Unsupported image format or file could not be read.",
            hierarchyRoot = maestro.viewHierarchy().root,
            debugMessage = "The assertScreenshot command requires a valid image file. Supported formats include PNG, JPEG, GIF, BMP, TIFF, and WBMP. The file at ${expectedFile.absolutePath} could not be read."
        )

        val diffFile = expectedFile.resolveSibling("${expectedFile.nameWithoutExtension}_diff.png")

        when (val result = ScreenshotMatch.compare(expectedImage, actualImage, thresholdPercentage, diffFile)) {
            is ScreenshotMatch.Result.Match -> return false // Screenshots are non-interactive
            is ScreenshotMatch.Result.SizeMismatch -> throw MaestroException.AssertionFailure(
                message = "Screenshot size mismatch: ${command.description()} - expected ${result.expectedWidth}x${result.expectedHeight}, actual ${result.actualWidth}x${result.actualHeight}. Screenshots must have the same dimensions to compare.",
                hierarchyRoot = maestro.viewHierarchy().root,
                debugMessage = "The assertScreenshot command requires the actual screenshot to have the same dimensions as the reference. Expected: ${result.expectedWidth}x${result.expectedHeight}, got: ${result.actualWidth}x${result.actualHeight}. Use the same device/emulator or cropOn to align dimensions."
            )
            is ScreenshotMatch.Result.Mismatch -> throw MaestroException.AssertionFailure(
                message = "Comparison error: ${command.description()} - threshold not met, current: ${result.matchPercent}%",
                hierarchyRoot = maestro.viewHierarchy().root,
                debugMessage = "Screenshot comparison failed. Check the diff image at ${diffFile.absolutePath} to see the differences. Adjust the thresholdPercentage if the differences are acceptable."
            )
        }
    }


    private fun evalScriptCommand(command: EvalScriptCommand): Boolean {
        command.scriptString.evaluateScripts(jsEngine)

        // Scripts can trigger HTTP requests that cause the app to receive a state change
        // (e.g. via WebSocket or push notification), mutating the hierarchy. We conservatively
        // treat these as mutating.
        return true
    }

    private suspend fun runScriptCommand(command: RunScriptCommand): Boolean {
        return if (evaluateCondition(command.condition, commandOptional = command.optional)) {
            jsEngine.evaluateScript(
                script = command.script,
                env = command.env,
                sourceName = command.sourceDescription,
                scriptDir = command.scriptDir,
                runInSubScope = true,
            )

            // Scripts can trigger HTTP requests that cause the app to receive a state change
            // (e.g. via WebSocket or push notification), mutating the hierarchy. We conservatively
            // treat these as mutating.
            true
        } else {
            throw CommandSkipped
        }
    }

    private fun defineVariablesCommand(command: DefineVariablesCommand): Boolean {
        command.env.forEach { (name, value) ->
            jsEngine.putEnv(name, value)
        }

        return false
    }

    private suspend fun repeatCommand(command: RepeatCommand, maestroCommand: MaestroCommand, config: MaestroConfig?): Boolean {
        val maxRuns = command.times?.toDoubleOrNull()?.toInt() ?: Int.MAX_VALUE

        var counter = 0
        var metadata = getMetadata(maestroCommand)
        metadata = metadata.copy(
            numberOfRuns = 0,
        )

        var mutating = false

        suspend fun checkCondition(): Boolean {
            return command.condition
                ?.evaluateScripts(jsEngine)
                ?.let { evaluateCondition(it, commandOptional = command.optional) } != false
        }

        while (checkCondition() && counter < maxRuns) {
            yield()
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

        // Retry is intended for flaky test-level failures — element not found, assertion
        // failures, etc. — which all surface as MaestroException. Anything else (driver
        // transport failures, JS evaluation bugs, CancellationException) propagates naturally.
        var attempt = 0
        while (attempt <= maxRetries) {
            try {
                return runSubFlow(command.commands, config, command.config)
            } catch (exception: MaestroException) {
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
        dispatch("onCommandMetadataUpdate") { it.onCommandMetadataUpdate(rawCommand, metadata) }
    }

    private fun getMetadata(rawCommand: MaestroCommand) = rawCommandToMetadata.getOrPut(rawCommand) {
        CommandMetadata()
    }

    private fun resetCommand(command: MaestroCommand) {
        dispatch("onCommandReset") { it.onCommandReset(command) }
        onCommandReset(command)

        (command.asCommand() as? CompositeCommand)?.let {
            it.subCommands().forEach { command ->
                resetCommand(command)
            }
        }
    }

    private fun dispatchFinished(
        command: MaestroCommand,
        outcome: CommandOutcome,
        sequenceNumber: Int,
    ) {
        val finishedAt = System.currentTimeMillis()
        val startedAt = commandStartTimes.remove(sequenceNumber) ?: finishedAt
        dispatch("onCommandFinished") {
            it.onCommandFinished(command, outcome, startedAt, finishedAt)
        }
    }

    /** Dispatches [block] to every listener in isolation — a thrower is logged, the rest still fire. */
    private inline fun dispatch(event: String, block: (OrchestraListener) -> Unit) {
        effectiveListeners.forEach { listener ->
            runCatching { block(listener) }
                .onFailure { e ->
                    logger.error(
                        "OrchestraListener ${listener::class.simpleName} threw on $event — " +
                            "flow continues, other listeners unaffected.",
                        e,
                    )
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

    private suspend fun evaluateCondition(
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
            val disappeared = MaestroTimer.withTimeoutSuspend(adjustedToLatestInteraction(timeoutMs ?: optionalLookupTimeoutMs)) {
                try {
                    findElement(
                        selector = it,
                        timeoutMs = 500L,
                        optional = commandOptional,
                    )
                    // Element is still visible
                    null
                } catch (ignored: MaestroException.ElementNotFound) {
                    // Element was not visible, as we expected
                    true
                }
            }

            if (disappeared != true) {
                return false
            }
        }

        return true
    }

    private suspend fun executeSubflowCommands(commands: List<MaestroCommand>, config: MaestroConfig?): Boolean {
        jsEngine.enterScope()
        subflowDepth++

        return try {
            commands
                .mapIndexed { index, command ->
                    yield()
                    onCommandStart(index, command)
                    val sequenceNumber = commandSequenceCounter++
                    val startedAt = System.currentTimeMillis()
                    commandStartTimes[sequenceNumber] = startedAt
                    dispatch("onCommandStart") { it.onCommandStart(command, sequenceNumber, subflowDepth) }

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
                                    dispatchFinished(command, CommandOutcome.Completed, sequenceNumber)
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
                        dispatchFinished(command, CommandOutcome.Warned, sequenceNumber)
                        onCommandWarned(index, command)
                        false
                    } catch (ignored: CommandSkipped) {
                        // Swallow exception
                        logger.info("[Command execution subflow] CommandSkipped: ${ignored.message}")
                        dispatchFinished(command, CommandOutcome.Skipped, sequenceNumber)
                        onCommandSkipped(index, command)
                        false
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DeviceConnectionException) {
                        throw e
                    } catch (e: Throwable) {
                        dispatchFinished(command, CommandOutcome.Failed(e), sequenceNumber)
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
            subflowDepth--
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw e
            } finally {
                onCompleteSuccess = if (currentCoroutineContext().isActive) {
                    subflowConfig?.onFlowComplete?.commands?.let {
                        executeSubflowCommands(it, config)
                    } ?: true
                } else {
                    true
                }
            }
            onCompleteSuccess && flowSuccess
        } finally {
            jsEngine.leaveEnvScope()
        }
    }

    private suspend fun takeScreenshotCommand(command: TakeScreenshotCommand): Boolean {
        ArtifactCollector.validateCommandPath(command.path, "takeScreenshot")
        // Generator owns the bundle path and records the file; null means no bundle (write CWD-relative).
        val outFile = artifactsGenerator
            .allocateCommandArtifact(ArtifactKind.TAKE_SCREENSHOT, "${command.path}.png", "takeScreenshot")
            ?: File("${command.path}.png")
        val fileSink = artifactSink(outFile, command.path, "takeScreenshot")

        val cropOn = command.cropOn
        if (cropOn == null) {
            maestro.takeScreenshot(fileSink, false)
        } else {
            val elementResult = findElement(cropOn, optional = command.optional)
            val bounds = elementResult.element.bounds
            if (bounds.width <= 0 || bounds.height <= 0) {
                throw MaestroException.AssertionFailure(
                    message = "Cannot crop screenshot: element '${cropOn.description()}' has invalid dimensions (width: ${bounds.width}, height: ${bounds.height}). The element must have positive width and height to crop the screenshot.",
                    hierarchyRoot = maestro.viewHierarchy().root,
                    debugMessage = "The takeScreenshot command with cropOn requires an element with positive dimensions. The found element has bounds: x=${bounds.x}, y=${bounds.y}, width=${bounds.width}, height=${bounds.height}."
                )
            }
            maestro.takeScreenshot(fileSink, false, bounds)
        }
        return false
    }

    private suspend fun startRecordingCommand(command: StartRecordingCommand): Boolean {
        ArtifactCollector.validateCommandPath(command.path, "startRecording")
        // Recorded at start; the file is finalized at stopRecording.
        val outFile = artifactsGenerator
            .allocateCommandArtifact(ArtifactKind.START_SCREEN_RECORDING, "${command.path}.mp4", "startRecording")
            ?: File("${command.path}.mp4")
        screenRecording = maestro.startScreenRecording(artifactSink(outFile, command.path, "startRecording"))
        return false
    }

    /** A raw IOException here reads as infra and gets retried, so a failed write is reported as a flow error. */
    private fun artifactSink(file: File, path: String, commandName: String) = try {
        file.apply { parentFile?.mkdirs() }.sink().buffer()
    } catch (e: IOException) {
        throw MaestroException.DestinationIsNotWritable(
            "Cannot write $commandName output to \"$path\": ${e.message}",
            e,
        )
    }

    private fun stopRecordingCommand(): Boolean {
        screenRecording?.close()
        return false
    }

    private suspend fun findElement(
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
        // `selector.childOf` describes the parent to search within, not the child being looked for.
        val parentSelector = selector.childOf
        if (parentSelector != null) {
            var fullHierarchy = ViewHierarchy(TreeNode())
            val found = MaestroTimer.withTimeoutSuspend(timeout) {
                fullHierarchy = maestro.viewHierarchy()
                val parentHierarchy = resolveParentHierarchy(parentSelector, fullHierarchy)
                parentHierarchy?.let { filterFunc(it.aggregate()).firstOrNull()?.toUiElementOrNull() }
            }
            if (found == null) {
                // Both "parent never matched" and "parent matched but child isn't in it" leave `found`
                // null, so re-resolve the parent against the last hierarchy we saw to say which it was.
                // Best effort: a hierarchy that is still changing may resolve differently here than it
                // did on the final loop iteration.
                val parentHierarchy = resolveParentHierarchy(parentSelector, fullHierarchy)
                val (parentDescription, _) = buildFilter(parentSelector)
                // Describe the target without its own childOf clause, so the two roles read
                // distinctly instead of repeating the parent back inside the target.
                val (targetDescription, targetFilter) = buildFilter(selector.copy(childOf = null))
                // A target with no criteria of its own filters to everything, so a count would be
                // meaningless - skip it rather than report the size of the hierarchy.
                val targetMatchesOnScreen =
                    if (targetDescription.isBlank()) null
                    else targetFilter(fullHierarchy.aggregate()).size
                val childOfDebugMessage = childOfDebugMessage(
                    parentMatched = parentHierarchy != null,
                    parentDescription = parentDescription,
                    targetDescription = targetDescription,
                    targetMatchesOnScreen = targetMatchesOnScreen,
                    timeoutMs = timeout,
                )
                if (parentHierarchy == null) {
                    throw MaestroException.ElementNotFound(
                        if (targetDescription.isBlank()) "Parent element not found: $parentDescription"
                        else "Parent element not found: $parentDescription (looking for $targetDescription inside it)",
                        fullHierarchy.root,
                        debugMessage = childOfDebugMessage
                    )
                }
                throw MaestroException.ElementNotFound(
                    "Element not found: $description",
                    fullHierarchy.root,
                    debugMessage = childOfDebugMessage
                )
            }
            return FindElementResult(found, ViewHierarchy(found.treeNode))
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

    /**
     * Debug text for a failed childOf lookup. Names which half of the selector failed, and whether the
     * target exists on screen outside the parent - that is what separates "my childOf is wrong" from
     * "the element really isn't there". Reaches the console and commands.json, not maestro.log.
     *
     * [targetMatchesOnScreen] is null when the target has no criteria of its own to count.
     */
    private fun childOfDebugMessage(
        parentMatched: Boolean,
        parentDescription: String,
        targetDescription: String,
        targetMatchesOnScreen: Int?,
        timeoutMs: Long,
    ): String {
        val whatFailed = if (parentMatched) {
            "The childOf parent ($parentDescription) matched, but $targetDescription was not found inside it."
        } else {
            "The childOf parent ($parentDescription) matched no element, so its children were never searched."
        }

        val elsewhere = when {
            targetMatchesOnScreen == null -> null
            targetMatchesOnScreen > 0 && parentMatched ->
                "$targetMatchesOnScreen element(s) matched $targetDescription elsewhere on screen, outside that parent."
            targetMatchesOnScreen > 0 ->
                "$targetMatchesOnScreen element(s) matched $targetDescription elsewhere on screen, " +
                    "so the childOf parent is the likely problem."
            else -> "Nothing matched $targetDescription anywhere on screen either."
        }

        // Report the window this lookup actually waited, not the configured timeout: it has already had
        // time since the last interaction deducted (see adjustedToLatestInteraction), so quoting it as
        // "the lookup timeout" would name a number the flow never set.
        val causes = if (parentMatched) {
            """
            - The element may sit outside the parent you selected - check the UI hierarchy in debug artifacts.
            - The element may not have rendered within the ${timeoutMs}ms this lookup waited.
            """.trimIndent()
        } else {
            """
            - The childOf selector may be incorrect - check the UI hierarchy in debug artifacts for elements with slightly different names/properties.
            - The parent may not have rendered within the ${timeoutMs}ms this lookup waited.
            """.trimIndent()
        }

        return listOfNotNull(whatFailed, elsewhere).joinToString(" ") + "\n\nPossible causes:\n" + causes
    }

    private fun resolveParentHierarchy(
        selector: ElementSelector?,
        hierarchy: ViewHierarchy,
    ): ViewHierarchy? {
        if (selector == null) return hierarchy
        val grandparentHierarchy = resolveParentHierarchy(selector.childOf, hierarchy) ?: return null
        val (_, parentFilter) = buildFilter(selector)
        return parentFilter(grandparentHierarchy.aggregate()).firstOrNull()
            ?.let { ViewHierarchy(it) }
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
                relativeFilters += Filters.containsChild(buildFilter(it).filterFunc)
            }

        selector.containsDescendants
            ?.let { descendantSelectors ->
                val descendantDescriptions = descendantSelectors.joinToString("; ") { it.description() }
                descriptions += "Contains descendants: $descendantDescriptions"
                relativeFilters += Filters.containsDescendants(descendantSelectors.map { buildFilter(it).filterFunc })
            }

        selector.childOf
            ?.let {
                descriptions += "Child of: ${it.description()}"
            }

        selector.traits
            ?.map {
                TraitFilters.buildFilter(it)
            }
            ?.forEach { (description, filter) ->
                descriptions += description
                basicFilters += filter
            }

        selector.index
            ?.let {
                descriptions += "Index: ${it.toDoubleOrNull()?.toInt() ?: it}"
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

    private fun adjustedToLatestInteraction(timeMs: Long) = max(
        0,
        timeMs - (System.currentTimeMillis() - timeMsOfLastInteraction),
    )

    private fun setClipboardCommand(command: SetClipboardCommand): Boolean {
        copiedText = command.text
        jsEngine.setCopiedText(copiedText)

        // Internal variable setting - no UI effect
        return false
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

