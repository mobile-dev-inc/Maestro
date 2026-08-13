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
import io.grpc.Status
import maestro.*
import maestro.Maestro
import maestro.DeviceConnectionException
import maestro.MaestroException
import maestro.Point
import maestro.ScreenRecording
import maestro.ai.cloud.Defect
import maestro.ai.CloudAIPredictionEngine
import maestro.ai.AIPredictionEngine
import maestro.device.Platform
import maestro.js.GraalJsEngine
import maestro.js.JsEngine
import maestro.orchestra.ArtifactKind
import maestro.orchestra.ArtifactManifest
import maestro.orchestra.debug.ArtifactsGenerator
import maestro.orchestra.devicecore.AssertMode
import maestro.orchestra.devicecore.DeviceCoreDriver
import maestro.orchestra.devicecore.RealDeviceCoreDriver
import maestro.orchestra.debug.BundleLayout
import maestro.orchestra.debug.ArtifactCollector
import maestro.orchestra.debug.CommandOutcome
import maestro.orchestra.debug.FlowDebugOutput
import maestro.orchestra.debug.OrchestraListener
import maestro.orchestra.geo.Traveller
import maestro.orchestra.util.Env.evaluateScripts
import maestro.orchestra.yaml.YamlCommandReader
import maestro.toSwipeDirection
import maestro.utils.Insight
import maestro.utils.Insights
import maestro.utils.NoopInsights
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
    // W1 transitional scaffold: removed in W1.6 when Orchestra drops the Maestro param.
    // Defaulted (rather than plumbed through every existing call site) because nothing
    // in this task calls driver.* yet — TestCommand's legacy path (the one this task
    // actually wires up) passes a session-provisioned instance explicitly; every other
    // caller keeps working unchanged off this default.
    private val driver: DeviceCoreDriver = RealDeviceCoreDriver(),
    // The session-resolved device platform (open Q4: platform is a session concern,
    // never a seam throw). The caller sources this from wherever it resolved the
    // device — MaestroSessionManager, for both the legacy Maestro path and the
    // Maestro-less device-core path — so platform-gated conditionals keep working
    // after W1.6 removes `maestro` from Orchestra entirely. Null only for callers not
    // yet updated to pass it explicitly; falls back to the legacy `maestro.cachedDeviceInfo`
    // roundtrip in that case (see [resolvedPlatform]).
    private val platform: Platform? = null,
    private val artifactsDir: Path? = null,
    private val captureFullArtifacts: Boolean = false,
    private val listeners: List<OrchestraListener> = emptyList(),
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
        // Inlined rather than calling the resolvedPlatform property below: default parameter
        // expressions are resolved in the primary constructor's own scope, which can't see members
        // declared in the class body — only other constructor parameters (platform, maestro).
        val platformName = (platform ?: maestro.cachedDeviceInfo.platform).toString().lowercase()
        httpClient?.let { GraalJsEngine(it, platformName) } ?: GraalJsEngine(platform = platformName)
    },
) {

    // The platform this Orchestra runs against: the constructor-supplied [platform] when the
    // caller has one (both MaestroSessionManager session builders do), otherwise the legacy
    // `maestro.cachedDeviceInfo` roundtrip. Never touches [driver] — a device-core roundtrip
    // for platform would risk hitting a roadmap verb that throws NotImplemented.
    private val resolvedPlatform: Platform get() = platform ?: maestro.cachedDeviceInfo.platform

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
        driver.setAndroidChromeDevToolsEnabled(shouldEnableAndroidChromeDevTools)
    }

    /**
     * Returns true if the command mutated device state (i.e. interacted with the device), false otherwise.
     */
    private suspend fun executeCommand(maestroCommand: MaestroCommand, config: MaestroConfig?): Boolean {
        val command = maestroCommand.asCommand()

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
            is AssertScreenshotCommand -> assertScreenshotCommand(command)
            is AssertConditionCommand -> assertConditionCommand(command)
            is AssertNoDefectsWithAICommand -> assertNoDefectsWithAICommand(command, maestroCommand)
            is AssertWithAICommand -> assertWithAICommand(command, maestroCommand)
            is ExtractTextWithAICommand -> extractTextWithAICommand(command, maestroCommand)
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
            is SetDarkModeCommand -> setDarkMode(command)
            is ToggleDarkModeCommand -> toggleDarkMode()
            is AssertDarkModeCommand -> assertDarkMode(expected = true)
            is AssertLightModeCommand -> assertDarkMode(expected = false)
            is RetryCommand -> retryCommand(command, config)
            else -> true
        }.also { mutating ->
            if (mutating) {
                timeMsOfLastInteraction = System.currentTimeMillis()
            }
        }
    }

    private suspend fun setAirplaneMode(command: SetAirplaneModeCommand): Boolean {
        when (command.value) {
            AirplaneValue.Enable -> driver.setAirplaneModeState(true)
            AirplaneValue.Disable -> driver.setAirplaneModeState(false)
        }

        return true
    }

    private suspend fun toggleAirplaneMode(): Boolean {
        driver.setAirplaneModeState(!driver.isAirplaneModeEnabled())
        return true
    }

    private suspend fun setDarkMode(command: SetDarkModeCommand): Boolean {
        when (command.value) {
            DarkModeValue.Enable -> driver.setDarkModeState(true)
            DarkModeValue.Disable -> driver.setDarkModeState(false)
        }

        return true
    }

    private suspend fun toggleDarkMode(): Boolean {
        driver.setDarkModeState(!driver.isDarkModeEnabled())
        return true
    }

    private suspend fun assertDarkMode(expected: Boolean): Boolean {
        val actual = driver.isDarkModeEnabled()
        if (actual != expected) {
            val expectedState = if (expected) "enabled" else "disabled"
            val actualState = if (actual) "dark mode" else "light mode"
            throw MaestroException.AssertionFailure(
                message = "Assertion failed: expected dark mode to be $expectedState, but it was ${if (actual) "enabled" else "disabled"}",
                hierarchyRoot = null,
                debugMessage = "The device's system-wide appearance is currently $actualState. Use setDarkMode or toggleDarkMode to change it before this assertion."
            )
        }

        return false
    }

    private suspend fun travelCommand(command: TravelCommand): Boolean {
        Traveller.travel(
            maestro = maestro,
            points = command.points,
            speedMPS = command.speedMPS ?: 4.0,
        )

        return true
    }

    private suspend fun addMediaCommand(mediaPaths: List<String>): Boolean {
        driver.addMedia(mediaPaths)
        return true
    }

    private suspend fun assertConditionCommand(command: AssertConditionCommand): Boolean {
        // W1.3: the device-core seam resolves visibility single-shot off device-core's OWN settle signal.
        // A DEFAULT-timeout assert keeps that blessed single-shot routing, but an EXPLICIT user timeout on
        // a visibility assert (extendedWaitUntil, or assertVisible/assertNotVisible with `timeout:`) asks
        // for a configurable wait — a roadmap capability the seam can't honor. Fail LOUD rather than
        // silently drop the user's timeout, matching every other dropped modifier in this task.
        val hasVisibilityCondition = command.condition.visible != null || command.condition.notVisible != null
        if (hasVisibilityCondition && command.timeoutMs() != null) {
            throw MaestroException.NotImplemented("assertVisibility configurable wait/timeout (extendedWaitUntil)")
        }
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
                hierarchyRoot = null,
                debugMessage = debugMessage
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
        driver.takeScreenshot(imageData, compressed = false)

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
                hierarchyRoot = null,
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
        driver.takeScreenshot(imageData, compressed = false)
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
                hierarchyRoot = null,
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
        driver.takeScreenshot(imageData, compressed = false)
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
                hierarchyRoot = null,
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
                hierarchyRoot = null,
                debugMessage = "The assertScreenshot command requires a pre-existing reference screenshot. " +
                    "Create it at one of the searched locations above."
            )

        expectedFile.parentFile?.mkdirs()

        // Temp file is always PNG since the screenshot verb produces PNG
        val actualScreenshotFile = File
            .createTempFile("screenshot-${System.currentTimeMillis()}", ".png")
            .also { it.deleteOnExit() }

        val cropOn = command.cropOn
        if (cropOn != null) {
            // Cropping to an element needs that element's on-screen bounds, which the legacy matching
            // engine resolved Maestro-side. Device-core owns element resolution now and a cropped
            // screenshot is a roadmap seam capability, so route through the seam to surface
            // NotImplemented instead of resolving bounds here.
            driver.takeScreenshot(actualScreenshotFile.sink(), false, cropOn)
        } else {
            driver.takeScreenshot(actualScreenshotFile.sink(), false)
        }

        val actualImage: BufferedImage = ImageIO.read(actualScreenshotFile)

        val expectedImage: BufferedImage = ImageIO.read(expectedFile) ?: throw MaestroException.AssertionFailure(
            message = "Failed to read image file: ${expectedFile.absolutePath}. Unsupported image format or file could not be read.",
            hierarchyRoot = null,
            debugMessage = "The assertScreenshot command requires a valid image file. Supported formats include PNG, JPEG, GIF, BMP, TIFF, and WBMP. The file at ${expectedFile.absolutePath} could not be read."
        )

        val diffFile = expectedFile.resolveSibling("${expectedFile.nameWithoutExtension}_diff.png")

        when (val result = ScreenshotMatch.compare(expectedImage, actualImage, thresholdPercentage, diffFile)) {
            is ScreenshotMatch.Result.Match -> return false // Screenshots are non-interactive
            is ScreenshotMatch.Result.SizeMismatch -> throw MaestroException.AssertionFailure(
                message = "Screenshot size mismatch: ${command.description()} - expected ${result.expectedWidth}x${result.expectedHeight}, actual ${result.actualWidth}x${result.actualHeight}. Screenshots must have the same dimensions to compare.",
                hierarchyRoot = null,
                debugMessage = "The assertScreenshot command requires the actual screenshot to have the same dimensions as the reference. Expected: ${result.expectedWidth}x${result.expectedHeight}, got: ${result.actualWidth}x${result.actualHeight}. Use the same device/emulator or cropOn to align dimensions."
            )
            is ScreenshotMatch.Result.Mismatch -> throw MaestroException.AssertionFailure(
                message = "Comparison error: ${command.description()} - threshold not met, current: ${result.matchPercent}%",
                hierarchyRoot = null,
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

    private suspend fun waitForAnimationToEndCommand(command: WaitForAnimationToEndCommand): Boolean {
        driver.waitForAnimationToEnd(command.timeout)

        return true
    }

    private fun defineVariablesCommand(command: DefineVariablesCommand): Boolean {
        command.env.forEach { (name, value) ->
            jsEngine.putEnv(name, value)
        }

        return false
    }

    private suspend fun setLocationCommand(command: SetLocationCommand): Boolean {
        driver.setLocation(command.latitude, command.longitude)

        return true
    }

    private suspend fun setOrientationCommand(command: SetOrientationCommand): Boolean {
        driver.setOrientation(command.resolvedOrientation())

        return true
    }

    private suspend fun clearAppStateCommand(command: ClearStateCommand): Boolean {
        driver.clearAppState(command.appId)
        // Android's clear command also resets permissions
        // Reset all permissions to unset so both platforms behave the same
        driver.setPermissions(command.appId, mapOf("all" to "unset"))

        return true
    }

    private suspend fun stopAppCommand(command: StopAppCommand): Boolean {
        driver.stopApp(command.appId)

        return true
    }

    private suspend fun killAppCommand(command: KillAppCommand): Boolean {
        driver.killApp(command.appId)

        return true
    }

    private suspend fun scrollVerticalCommand(): Boolean {
        driver.scrollVertical()
        return true
    }

    private suspend fun scrollUntilVisible(command: ScrollUntilVisibleCommand): Boolean {
        // scrollUntilVisible scrolls a container while polling the target element's on-screen
        // visibility percentage (and optionally centering it), stopping once it crosses the
        // threshold. That per-step visibility measurement is on-device element geometry the legacy
        // matching engine computed Maestro-side; device-core owns element resolution now and exposes
        // no visibility-poll capability yet (roadmap). Route the scroll through the seam so the
        // command surfaces NotImplemented rather than silently no-oping or degrading to a one-shot
        // assert.
        val direction = command.direction.toSwipeDirection()
        driver.swipeFromCenter(
            direction,
            durationMs = command.scrollDuration.toLong(),
            waitToSettleTimeoutMs = command.waitToSettleTimeoutMs,
        )
        return true
    }

    private suspend fun hideKeyboardCommand(): Boolean {
        driver.hideKeyboard()

        // Throw error in case keyboard is still visible
        if (driver.isKeyboardVisible()) {
            throw MaestroException.HideKeyboardFailure(
                "Couldn't hide the keyboard. This can happen if the app uses a custom input or doesn't expose a standard dismiss action.",
                debugMessage = """
                    Instead of hideKeyboard, try tapping on non-interactive element to hide keyboard. Example:
 
                    - tapOn: 
                        text: 'Static Text on your screen'
                """.trimIndent()
            )
        }

        return true
    }

    private suspend fun backPressCommand(): Boolean {
        driver.backPress()
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
            if (it != resolvedPlatform) {
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
            // W1.3: visibility resolves through the device-core seam off device-core's OWN visibility
            // signal — no Maestro-side geometry, no polling (device-core owns settling). The seam throws
            // AssertionFailure on a clean false verdict; here that means "condition is false" -> return
            // false, preserving evaluateCondition's boolean contract (the caller decides fail vs. skip).
            // A roadmap selector (NotImplemented) or an infra failure (DeviceUnreachable) still
            // propagates from the seam — a non-routable guard is never silently treated as true/false.
            // (When the enclosing command is optional, the propagated MaestroException is swallowed to a
            // warning by the executeCommands optional handler, matching the existing optional semantics.)
            try {
                driver.assertVisibility(it, AssertMode.VISIBLE)
            } catch (_: MaestroException.AssertionFailure) {
                return false
            }
        }

        condition.notVisible?.let {
            try {
                driver.assertVisibility(it, AssertMode.NOT_VISIBLE)
            } catch (_: MaestroException.AssertionFailure) {
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
            driver.takeScreenshot(fileSink, false)
        } else {
            // Element-cropped screenshot needs the element's bounds (legacy matching engine) and is a
            // roadmap seam capability; route through the seam so it surfaces NotImplemented.
            driver.takeScreenshot(fileSink, false, cropOn)
        }
        return false
    }

    private suspend fun startRecordingCommand(command: StartRecordingCommand): Boolean {
        ArtifactCollector.validateCommandPath(command.path, "startRecording")
        // Recorded at start; the file is finalized at stopRecording.
        val outFile = artifactsGenerator
            .allocateCommandArtifact(ArtifactKind.START_SCREEN_RECORDING, "${command.path}.mp4", "startRecording")
            ?: File("${command.path}.mp4")
        screenRecording = driver.startScreenRecording(artifactSink(outFile, command.path, "startRecording"))
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

    private suspend fun eraseTextCommand(command: EraseTextCommand): Boolean {
        val charactersToErase = command.charactersToErase
        driver.eraseText(charactersToErase ?: MAX_ERASE_CHARACTERS)
        driver.waitForAppToSettle()

        return true
    }

    private suspend fun pressKeyCommand(command: PressKeyCommand): Boolean {
        driver.pressKey(command.code)

        return true
    }

    private suspend fun openLinkCommand(command: OpenLinkCommand, config: MaestroConfig?): Boolean {
        driver.openLink(command.link, config?.appId, command.autoVerify ?: false, command.browser ?: false)

        return true
    }

    private suspend fun launchAppCommand(command: LaunchAppCommand): Boolean {
        // W1.5: the launch itself routes through the device-core seam, which takes ONLY appId. Every
        // launch modifier is a roadmap capability the seam can't carry, so each is guarded here with
        // DeviceCoreFlowRunner's exact NotImplemented message rather than calling the corresponding
        // seam verb (which would throw a generic message, or — for the legacy default allow-all
        // setPermissions — throw on EVERY launch and sink the built launchApp verb). A modifier-free
        // launch reaches driver.launchApp and succeeds, exactly as the four-command vertical requires.
        if (command.clearState == true) {
            throw MaestroException.NotImplemented("launchApp modifier clearState")
        }
        if (command.clearKeychain == true) {
            throw MaestroException.NotImplemented("launchApp modifier clearKeychain")
        }
        // Only an EXPLICIT permissions modifier throws; the legacy default allow-all is never applied
        // (device-core owns launch-time permissions), matching the runner.
        if (command.permissions != null) {
            throw MaestroException.NotImplemented("launchApp modifier permissions")
        }
        if (!command.launchArguments.isNullOrEmpty()) {
            throw MaestroException.NotImplemented("launchApp modifier launchArguments")
        }
        if (command.stopApp == false) {
            throw MaestroException.NotImplemented("launchApp modifier stopApp")
        }
        driver.launchApp(command.appId)

        return true
    }

    private suspend fun setPermissionsCommand(command: SetPermissionsCommand): Boolean {
        driver.setPermissions(command.appId, command.permissions)

        // Setting permissions occurs behind the scenes and won't alter screen state.
        // Android and iOS provide no mechanism for subscribing to permissions events.
        return false
    }

    private suspend fun clearKeychainCommand(): Boolean {
        driver.clearKeychain()

        // No UI effect
        return false
    }

    private suspend fun inputTextCommand(command: InputTextCommand): Boolean {
        driver.inputText(command.text)

        return true
    }

    private suspend fun inputTextRandomCommand(command: InputRandomCommand): Boolean {
        inputTextCommand(InputTextCommand(text = command.genRandomString()))

        return true
    }

    private suspend fun assertCommand(command: AssertCommand): Boolean {
        return assertConditionCommand(
            command.toAssertConditionCommand()
        )
    }

    private suspend fun tapOnElement(
        command: TapOnElementCommand,
        retryIfNoChange: Boolean,
        waitUntilVisible: Boolean,
        config: MaestroConfig?,
    ): Boolean {
        // Element-relative point tap places the tap at a point within the resolved element's bounds,
        // which the legacy matching engine resolved Maestro-side. Device-core owns element resolution
        // now and exposes no element-anchored point tap yet (roadmap, W1.5b), so guard it like the
        // other unsupported tap modifiers rather than resolving bounds here.
        if (command.relativePoint != null) {
            throw MaestroException.NotImplemented("tapOnElement modifier relativePoint")
        }
        // W1.3: selector-based tap routes through the device-core seam, which resolves the element
        // itself and drops longPress/repeat. Both are guarded here with DeviceCoreFlowRunner's exact
        // NotImplemented messages. The seam takes the raw ElementSelector and translates it via
        // SelectorTranslator internally (an unsupported selector field throws NotImplemented there).
        if (command.longPress == true) {
            throw MaestroException.NotImplemented("tapOnElement modifier longPress")
        }
        if (command.repeat != null) {
            throw MaestroException.NotImplemented("tapOnElement modifier repeat")
        }
        driver.tap(command.selector)

        return true
    }

    private suspend fun tapOnPoint(
        command: TapOnPointCommand,
        retryIfNoChange: Boolean,
    ): Boolean {
        driver.tapOnPoint(
            x = command.x,
            y = command.y,
            retryIfNoChange = retryIfNoChange,
            longPress = command.longPress ?: false,
            tapRepeat = command.repeat,
        )

        return true
    }

    private suspend fun tapOnPointV2Command(
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

            driver.tapOnRelative(
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

            driver.tapOnPoint(
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


    private suspend fun swipeCommand(command: SwipeCommand): Boolean {
        val elementSelector = command.elementSelector
        val direction = command.direction
        val startRelative = command.startRelative
        val endRelative = command.endRelative
        val start = command.startPoint
        val end = command.endPoint
        when {
            elementSelector != null && direction != null -> {
                // Swiping from a resolved element needs that element's on-screen geometry (its bounds
                // center, or a relative point within it) as the swipe start — geometry the legacy
                // matching engine resolved Maestro-side. Device-core owns element resolution now and
                // has no element-anchored swipe yet (roadmap), so route through the seam swipe verb to
                // surface NotImplemented rather than resolving bounds here.
                driver.swipe(
                    swipeDirection = direction,
                    duration = command.duration,
                    waitToSettleTimeoutMs = command.waitToSettleTimeoutMs,
                )
            }

            startRelative != null && endRelative != null -> {
                driver.swipe(
                    startRelative = startRelative,
                    endRelative = endRelative,
                    duration = command.duration,
                    waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
                )
            }

            direction != null -> driver.swipe(
                swipeDirection = direction,
                duration = command.duration,
                waitToSettleTimeoutMs = command.waitToSettleTimeoutMs
            )

            start != null && end != null -> driver.swipe(
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

    private suspend fun copyTextFromCommand(command: CopyTextFromCommand): Boolean {
        // Copying an element's text reads that element's text/hint/accessibility attributes from the
        // on-device view tree — the device hierarchy, which the seam does not expose yet (device-core
        // has no serializable tree; roadmap). Route through the seam so this surfaces NotImplemented
        // instead of silently copying empty text.
        driver.hierarchy()
    }

    private fun setClipboardCommand(command: SetClipboardCommand): Boolean {
        copiedText = command.text
        jsEngine.setCopiedText(copiedText)

        // Internal variable setting - no UI effect
        return false
    }

    private suspend fun pasteText(): Boolean {
        copiedText?.let { driver.inputText(it) }
        return true
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

