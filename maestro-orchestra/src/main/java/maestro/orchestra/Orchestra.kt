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
import io.grpc.Status
import maestro.*
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
import maestro.orchestra.devicecore.DeviceGateway
import maestro.orchestra.devicecore.RealDeviceGateway
import maestro.orchestra.debug.BundleLayout
import maestro.orchestra.debug.ArtifactCollector
import maestro.orchestra.debug.CommandOutcome
import maestro.orchestra.debug.FlowDebugOutput
import maestro.orchestra.debug.OrchestraListener
import maestro.orchestra.debug.StepTraceEmitter
import maestro.orchestra.devicecore.ChosenElement
import maestro.orchestra.devicecore.Verdict
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
 * Orchestra translates high-level Maestro commands into calls on the [DeviceGateway] seam — the
 * single interface every device verb goes through. It's the glue between the CLI and device-core.
 * It's one of the core classes in this codebase.
 *
 * Orchestra should not know about:
 *  - Specific platforms where tests can be executed, such as Android, iOS, or the web.
 *  - File systems. It should instead write to [Sink]s that it requests from the caller.
 */
class Orchestra(
    // The device-core seam Orchestra drives every device verb through. Defaults to an inert
    // RealDeviceGateway (never connected) so tests that do no device op need not supply one;
    // every real `maestro test` caller passes a session-provisioned, connected driver.
    private val driver: DeviceGateway = RealDeviceGateway(),
    // The session-resolved device platform. W1.6: the `maestro` facade is gone, so there is no
    // `maestro.cachedDeviceInfo` fallback anymore — platform is sourced ONLY from the session, which
    // every real `maestro test` path supplies (MaestroSessionManager resolves it before any flow
    // runs). Defaults to ANDROID for callers that don't run platform-gated conditionals (viewer,
    // tests): platform only feeds `platform:` conditions and the JS engine's platform string, never
    // a device roundtrip, so a default here can never mask a missing device connection.
    private val platform: Platform = Platform.ANDROID,
    // Spec-A differential trace. When non-null, Orchestra emits one PASS/FAIL/ERROR record per
    // finished command (see [dispatchFinished]) at the frozen [StepTraceEmitter] schema — the
    // behavior [maestro.orchestra.devicecore.DeviceCoreFlowRunner] used to own before it was retired.
    private val stepTraceEmitter: StepTraceEmitter? = null,
    private val artifactsDir: Path? = null,
    private val captureFullArtifacts: Boolean = false,
    private val listeners: List<OrchestraListener> = emptyList(),
    // THE single element-lookup deadline: explicit asserts (when no `timeout:` is given), `when:`/
    // `while:` guards, optional and non-optional alike. One knob by design — 2.x's two-tier split
    // (17s asserts / 7s guards+optional) is deliberately collapsed. 12s is DERIVED, not chosen:
    // p99 of successful element-appearance latency across the fidelity corpus (post-launch 10.89s,
    // steady-state 10.37s) plus ~10% margin — see validation-harness/LOOKUP_TIMEOUT_DERIVATION.md
    // for the method, data, and the two 2.x deviations this locks in. A flow's explicit `timeout:`
    // still overrides per command.
    private val lookupTimeoutMs: Long = 12000L,
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
        val platformName = platform.toString().lowercase()
        httpClient?.let { GraalJsEngine(it, platformName) } ?: GraalJsEngine(platform = platformName)
    },
) {

    // The platform this Orchestra runs against — the constructor-supplied [platform], sourced from
    // the session. Never touches [driver]: a device-core roundtrip for platform would risk hitting a
    // roadmap verb that throws NotImplemented.
    private val resolvedPlatform: Platform get() = platform

    private lateinit var jsEngine: JsEngine

    private var copiedText: String? = null

    // The element the current command resolved/acted on, captured from the seam's tap/assert return
    // for the step trace. Reset per command in [executeCommand]; only tap/assert leaves populate it.
    private var lastChosenElement: ChosenElement? = null

    private var screenRecording: ScreenRecording? = null

    private val rawCommandToMetadata = mutableMapOf<MaestroCommand, CommandMetadata>()

    // ArtifactsGenerator is always the first listener: it writes the bundle when
    // artifactsDir is set and populates debugOutput either way.
    private val artifactsGenerator: ArtifactsGenerator =
        ArtifactsGenerator(artifactsDir, driver, captureFullArtifacts, onStepScreenshotCaptured)
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
                    } catch (e: MaestroException.NotImplemented) {
                        // device-core hasn't built this verb. `optional` governs "the flow can proceed
                        // without this step"; NotImplemented means the opposite thing ("this device op
                        // does not exist yet") and must never be laundered by it — skip the optional
                        // check entirely and let it fall to the Throwable handler below, which always
                        // hard-stops it regardless of onCommandFailed's resolution.
                        throw e
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
                    if (e is MaestroException.NotImplemented) {
                        // The OWED wall: always hard-stop, ignoring onCommandFailed's resolution —
                        // even a CLI-style CONTINUE must not let the flow limp past a device op that
                        // does not exist yet.
                        return false
                    }
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
        // Only touch the seam when the flow actually opts into devtools webview hierarchy. The legacy
        // facade used a `false` call to reset state; device-core has no such state, and firing the verb
        // on every flow would throw NotImplemented (roadmap) before any command runs. When a flow DOES
        // request devtools, the seam is called and surfaces NotImplemented loudly — correct on
        // device-core, which can't serve devtools webview hierarchy yet.
        if (shouldEnableAndroidChromeDevTools) {
            driver.setAndroidChromeDevToolsEnabled(true)
        }
    }

    /**
     * Returns true if the command mutated device state (i.e. interacted with the device), false otherwise.
     */
    private suspend fun executeCommand(maestroCommand: MaestroCommand, config: MaestroConfig?): Boolean {
        val command = maestroCommand.asCommand()

        flowController.waitIfPaused()

        // Fresh per command: only a tap/assert leaf repopulates it before this command's
        // dispatchFinished reads it for the trace.
        lastChosenElement = null

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
                debugMessage = "The device's system-wide appearance is currently $actualState. Use setDarkMode or toggleDarkMode to change it before this assertion."
            )
        }

        return false
    }

    private suspend fun travelCommand(command: TravelCommand): Boolean {
        Traveller.travel(
            driver = driver,
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
        // Visibility resolves through the device-core seam's WAITED verb (waitFor). The effective
        // deadline — an explicit `timeout:` / extendedWaitUntil, else lookupTimeoutMs — is threaded
        // into the seam, which is the single source of the NotImplemented/verdict decision.
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
            debugMessage = "The assertScreenshot command requires a valid image file. Supported formats include PNG, JPEG, GIF, BMP, TIFF, and WBMP. The file at ${expectedFile.absolutePath} could not be read."
        )

        val diffFile = expectedFile.resolveSibling("${expectedFile.nameWithoutExtension}_diff.png")

        when (val result = ScreenshotMatch.compare(expectedImage, actualImage, thresholdPercentage, diffFile)) {
            is ScreenshotMatch.Result.Match -> return false // Screenshots are non-interactive
            is ScreenshotMatch.Result.SizeMismatch -> throw MaestroException.AssertionFailure(
                message = "Screenshot size mismatch: ${command.description()} - expected ${result.expectedWidth}x${result.expectedHeight}, actual ${result.actualWidth}x${result.actualHeight}. Screenshots must have the same dimensions to compare.",
                debugMessage = "The assertScreenshot command requires the actual screenshot to have the same dimensions as the reference. Expected: ${result.expectedWidth}x${result.expectedHeight}, got: ${result.actualWidth}x${result.actualHeight}. Use the same device/emulator or cropOn to align dimensions."
            )
            is ScreenshotMatch.Result.Mismatch -> throw MaestroException.AssertionFailure(
                message = "Comparison error: ${command.description()} - threshold not met, current: ${result.matchPercent}%",
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
        // device-core's `Locator.scrollTo` IS this command's semantic — a swipe loop bounded by the
        // caller's clock that stops when the locator resolves. Semantic knobs its verb doesn't carry
        // wall precisely; DEFAULTS pass through (full visibility is scrollTo's own resolved-and-
        // visible bar — note it reads the a11y `visibleToUser` flag, not a percentage; the harness
        // scores whether that difference is consequential). Speed/settle knobs (`scrollDuration`,
        // `waitToSettleTimeoutMs`) are gesture MECHANICS, which the strategy owns — not walled.
        if (command.visibilityPercentage != ScrollUntilVisibleCommand.DEFAULT_ELEMENT_VISIBILITY_PERCENTAGE) {
            throw MaestroException.NotImplemented("scrollUntilVisible modifier visibilityPercentage")
        }
        if (command.centerElement) {
            throw MaestroException.NotImplemented("scrollUntilVisible modifier centerElement")
        }
        lastChosenElement = driver.scrollUntilVisible(
            command.selector,
            command.direction,
            timeoutMs = command.timeout.toLongOrNull()
                ?: ScrollUntilVisibleCommand.DEFAULT_TIMEOUT_IN_MILLIS.toLong(),
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
        emitStepTrace(command, outcome, sequenceNumber)
    }

    /**
     * Emits the per-command differential-trace record the retired DeviceCoreFlowRunner used to write:
     * [Verdict.PASS] on a clean finish (Completed/Warned), [Verdict.FAIL] on a thrown
     * [MaestroException] (assert/tap failure or an unimplemented command/modifier), [Verdict.ERROR]
     * on any other throwable. Skipped commands emit nothing (the runner never traced its skipped
     * structural commands either). [lastChosenElement] carries the seam's resolved element for
     * tap/assert leaves; it's null for every other command.
     */
    private fun emitStepTrace(command: MaestroCommand, outcome: CommandOutcome, sequenceNumber: Int) {
        val emitter = stepTraceEmitter ?: return
        val (verdict, error) = when (outcome) {
            is CommandOutcome.Completed -> Verdict.PASS to null
            is CommandOutcome.Warned -> Verdict.PASS to null
            is CommandOutcome.Skipped -> return
            is CommandOutcome.Failed -> {
                val err = outcome.error
                when {
                    // The OWED wall: a device op device-core hasn't built yet is a distinct outcome
                    // from a regular assert/tap failure, not just another FAIL.
                    err is MaestroException.NotImplemented ->
                        Verdict.ERROR to StepTraceEmitter.StepError("NotImplemented", err.message)
                    err is MaestroException ->
                        Verdict.FAIL to StepTraceEmitter.StepError(err::class.simpleName ?: "MaestroException", err.message)
                    else ->
                        Verdict.ERROR to StepTraceEmitter.StepError(err::class.simpleName ?: "Throwable", err.message)
                }
            }
        }
        emitter.emit(
            stepIndex = sequenceNumber,
            commandType = command.asCommand()?.let { it::class.simpleName } ?: "null",
            selectorText = command.elementSelector()?.textRegex,
            selectorId = command.elementSelector()?.idRegex,
            verdict = verdict,
            chosen = lastChosenElement,
            error = error,
        )
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
            // Visibility resolves through the device-core seam's WAITED verb (waitFor) — no
            // Maestro-side geometry; device-core owns settling and internal polling, BOUNDED BY THE
            // DEADLINE THREADED HERE (its poll loop never invents its own wait). Guards get the ONE
            // locator timeout: the caller's explicit `timeout:` if given, else lookupTimeoutMs (see
            // the constructor note + LOOKUP_TIMEOUT_DERIVATION.md). NO interaction discount — a guard
            // is a full-budget lookup, never a starved one. The old discount could drain this to ~0
            // whenever a preceding lookup spent real time without resetting the interaction clock (a
            // failed optional tap, most visibly), and a 0-budget waitFor is structurally always-false
            // on the Android seam (readiness needs ~300ms of agreeing reads a zero clock can't hold);
            // the fidelity harness caught this on Vaulty 001. The seam throws AssertionFailure on a
            // clean false verdict; here that means "condition is
            // false" -> return false, preserving evaluateCondition's boolean contract (the caller
            // decides fail vs. skip). A roadmap selector (NotImplemented) or an infra failure
            // (DeviceUnreachable) still propagates from the seam — a non-routable guard is never
            // silently treated as true/false. (When the enclosing command is optional, the propagated
            // MaestroException is swallowed to a warning by the executeCommands optional handler.)
            try {
                lastChosenElement = driver.assertVisibility(
                    it,
                    AssertMode.VISIBLE,
                    timeoutMs ?: lookupTimeoutMs,
                )
            } catch (_: MaestroException.AssertionFailure) {
                return false
            }
        }

        condition.notVisible?.let {
            // Same single deadline as the visible arm. Inert until the seam serves waitFor(GONE) —
            // NOT_VISIBLE still throws NotImplemented — but the contract is stated once, here.
            try {
                lastChosenElement = driver.assertVisibility(
                    it,
                    AssertMode.NOT_VISIBLE,
                    timeoutMs ?: lookupTimeoutMs,
                )
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
                        } catch (e: MaestroException.NotImplemented) {
                            // Same OWED wall as the top-level command loop: never laundered by the
                            // optional check — falls to the Throwable handler below, which always
                            // hard-stops this subflow regardless of onCommandFailed's resolution.
                            throw e
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
                        val errorResolution = onCommandFailed(index, command, e)
                        if (e is MaestroException.NotImplemented) {
                            // Always hard-stop this subflow, ignoring the resolution.
                            throw e
                        }
                        when (errorResolution) {
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
        // The launch itself routes through the device-core seam, which takes ONLY appId. clearState
        // and permissions are served (device-core clearState/setPermission); each remaining modifier
        // is still a roadmap capability, guarded here with the exact NotImplemented message rather
        // than the seam default's generic one. A modifier-free launch reaches driver.launchApp
        // directly, exactly as the four-command vertical requires.
        // clearState before setPermissions before launch: Android's `pm clear` also resets runtime
        // permissions (see clearAppStateCommand's note), so grants applied before a clear would be lost.
        if (command.clearState == true) {
            driver.clearAppState(command.appId)
        }
        // clearKeychain is an iOS-only verb. Legacy Android's clearKeychain() is a literal no-op
        // (2.x AndroidDriver.kt), and device-core deliberately ships no Android realization
        // (ROADMAP: "iOS only — typed-refuse on Android"; GE4 forbids a silent no-op on ITS
        // surface, so the not-asking lives here, where legacy semantics are translated). iOS
        // still walls until device-core ships Device.clearKeychain.
        if (command.clearKeychain == true) {
            if (platform == Platform.IOS) {
                throw MaestroException.NotImplemented("launchApp modifier clearKeychain")
            }
            logger.info("clearKeychain ignored: iOS-only verb, a no-op on ${platform.name} — matching legacy")
        }
        // Default to allow-all when the flow sets no permissions — mirrors 2.x
        // (`command.permissions ?: mapOf("all" to "allow")`) so a launch surfaces no runtime
        // permission dialogs. Applied after clearState (which resets grants) and before launch, so
        // the app starts already granted; routes to device-core setPermission("all","allow").
        val permissions = command.permissions ?: mapOf("all" to "allow")
        driver.setPermissions(command.appId, permissions)
        if (command.stopApp == false) {
            throw MaestroException.NotImplemented("launchApp modifier stopApp")
        }
        // launchArguments thread through the seam to device-core's typed launch arguments
        // (`am start` extras) — Map<String, Any> on both sides, no translation.
        driver.launchApp(command.appId, command.launchArguments ?: emptyMap())

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
        // W1.3: selector-based tap/longPress route through the device-core seam, which resolves the
        // element itself. `repeat` is still guarded (device-core has no repeat verb). The seam takes
        // the raw ElementSelector and translates it via SelectorTranslator internally (an unsupported
        // selector field throws NotImplemented there).
        if (command.repeat != null) {
            throw MaestroException.NotImplemented("tapOnElement modifier repeat")
        }
        // Legacy tapOn's semantics INCLUDE an appearance wait (findElement(lookupTimeout) then act).
        // device-core unbundled that: Locator.tap/longPress(timeoutMs) is the appearance-and-gate
        // budget, spent by the strategy — the ONE locator timeout, full budget, no discount. longPress
        // holds the press past the system long-press threshold; otherwise identical to tap.
        lastChosenElement = if (command.longPress == true) {
            driver.longPress(command.selector, lookupTimeoutMs)
        } else {
            driver.tap(command.selector, lookupTimeoutMs)
        }

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

